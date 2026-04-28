#include <android/log.h>
#include <jni.h>
#include <sampling.h>

#include <mutex>
#include <string>
#include <vector>
#include <unistd.h>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "logging.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

constexpr int N_THREADS_MIN = 2;
constexpr int N_THREADS_MAX = 2;
constexpr int N_THREADS_HEADROOM = 2;
constexpr int DEFAULT_CONTEXT_SIZE = 1024;
constexpr int DEFAULT_BATCH_SIZE = 64;
constexpr float DEFAULT_SAMPLER_TEMP = 0.7f;

class MultimodalEngine {
public:
    void init(JNIEnv * env, jstring native_lib_dir) {
        std::lock_guard<std::mutex> lock(mutex_);
        llama_log_set(aichat_android_log_callback, nullptr);
        mtmd_log_set(aichat_android_log_callback, nullptr);

        const char * path = env->GetStringUTFChars(native_lib_dir, nullptr);
        LOGi("Loading multimodal backends from %s", path);
        ggml_backend_load_all_from_path(path);
        env->ReleaseStringUTFChars(native_lib_dir, path);
        if (ggml_backend_reg_count() == 0) {
            LOGw("No backends loaded from app native lib dir, falling back to ggml_backend_load_all()");
            ggml_backend_load_all();
        }
        LOGi("Registered backend count after multimodal init: %zu", ggml_backend_reg_count());
        for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
            auto *reg = ggml_backend_reg_get(i);
            LOGi("  Backend %zu: %s", i, ggml_backend_reg_name(reg));
        }

        llama_backend_init();
    }

    int load(JNIEnv * env, jstring j_model_path, jstring j_mmproj_path) {
        std::lock_guard<std::mutex> lock(mutex_);
        unloadLocked();

        const char * model_path = env->GetStringUTFChars(j_model_path, nullptr);
        const char * mmproj_path = env->GetStringUTFChars(j_mmproj_path, nullptr);

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 999;
        LOGi("Loading multimodal model from %s with mmproj %s (n_gpu_layers=%d)",
             model_path, mmproj_path, model_params.n_gpu_layers);
        model_ = llama_model_load_from_file(model_path, model_params);
        env->ReleaseStringUTFChars(j_model_path, model_path);
        if (!model_) {
            LOGe("llama_model_load_from_file returned null");
            env->ReleaseStringUTFChars(j_mmproj_path, mmproj_path);
            return 1;
        }

        llama_context_params ctx_params = llama_context_default_params();
        const int n_threads = std::max(
            N_THREADS_MIN,
            std::min(N_THREADS_MAX, (int) sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM)
        );
        ctx_params.n_ctx = DEFAULT_CONTEXT_SIZE;
        ctx_params.n_batch = DEFAULT_BATCH_SIZE;
        ctx_params.n_ubatch = DEFAULT_BATCH_SIZE;
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = n_threads;
        ctx_params.type_k = GGML_TYPE_Q8_0;
        ctx_params.type_v = GGML_TYPE_Q8_0;
        lctx_ = llama_init_from_model(model_, ctx_params);
        if (!lctx_) {
            LOGe("llama_init_from_model returned null");
            env->ReleaseStringUTFChars(j_mmproj_path, mmproj_path);
            unloadLocked();
            return 2;
        }

        common_params_sampling sampling_params;
        sampling_params.temp = DEFAULT_SAMPLER_TEMP;
        sampling_params.top_k = 20;
        sampling_params.top_p = 0.8f;
        sampling_params.min_p = 0.0f;
        sampling_params.penalty_present = 1.5f;
        sampling_params.penalty_repeat = 1.0f;
        sampler_ = common_sampler_init(model_, sampling_params);
        common_sampler_reset(sampler_);
        tmpls_ = common_chat_templates_init(model_, "");

        mtmd_context_params mtmd_params = mtmd_context_params_default();
        mtmd_params.use_gpu = true;
        mtmd_params.print_timings = false;
        mtmd_params.n_threads = n_threads;
        mtmd_params.warmup = false;
        mtmd_ = mtmd_init_from_file(mmproj_path, model_, mtmd_params);
        env->ReleaseStringUTFChars(j_mmproj_path, mmproj_path);
        if (!mtmd_) {
            LOGe("mtmd_init_from_file returned null");
            unloadLocked();
            return 3;
        }

        generation_batch_ = llama_batch_init(1, 0, 1);
        loaded_ = true;
        LOGi("Multimodal model loaded successfully");
        return 0;
    }

    std::string analyze(
        JNIEnv * env,
        jstring j_prompt,
        jstring j_image_path,
        jint predict_length
    ) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!loaded_) {
            LOGe("analyze called before model load");
            return {};
        }

        llama_memory_clear(llama_get_memory(lctx_), true);
        common_sampler_reset(sampler_);

        const char * prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
        const char * image_path = env->GetStringUTFChars(j_image_path, nullptr);

        std::string prompt(prompt_chars);
        if (prompt.find(mtmd_default_marker()) == std::string::npos) {
            prompt = std::string(mtmd_default_marker()) + "\n" + prompt;
        }
        env->ReleaseStringUTFChars(j_prompt, prompt_chars);

        common_chat_msg user_msg;
        user_msg.role = "user";
        user_msg.content = prompt;
        std::vector<common_chat_msg> chat_history;
        common_chat_templates_inputs chat_inputs;
        chat_inputs.use_jinja = common_chat_templates_was_explicit(tmpls_.get());
        chat_inputs.add_bos = false;
        chat_inputs.add_eos = false;
        chat_inputs.messages = chat_history;
        chat_inputs.messages.push_back(user_msg);
        chat_inputs.add_generation_prompt = true;
        chat_inputs.enable_thinking = common_chat_templates_support_enable_thinking(tmpls_.get());
        LOGi("Multimodal template supports thinking=%s", chat_inputs.enable_thinking ? "true" : "false");
        const auto formatted = common_chat_templates_apply(tmpls_.get(), chat_inputs).prompt;

        mtmd::bitmap bitmap(mtmd_helper_bitmap_init_from_file(mtmd_, image_path));
        env->ReleaseStringUTFChars(j_image_path, image_path);
        if (!bitmap.ptr) {
            LOGe("mtmd_helper_bitmap_init_from_file returned null");
            return {};
        }

        mtmd_input_text text {
            .text = formatted.c_str(),
            .add_special = true,
            .parse_special = true,
        };
        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        mtmd::bitmaps bitmaps;
        bitmaps.entries.push_back(std::move(bitmap));
        auto bitmap_ptrs = bitmaps.c_ptr();
        llama_pos new_n_past = 0;
        if (mtmd_tokenize(mtmd_, chunks.ptr.get(), &text, bitmap_ptrs.data(), bitmap_ptrs.size()) != 0) {
            LOGe("mtmd_tokenize failed");
            return {};
        }
        if (mtmd_helper_eval_chunks(mtmd_, lctx_, chunks.ptr.get(), 0, 0, DEFAULT_BATCH_SIZE, true, &new_n_past) != 0) {
            LOGe("mtmd_helper_eval_chunks failed");
            return {};
        }

        std::vector<llama_token> generated_tokens;
        for (int i = 0; i < predict_length; ++i) {
            const llama_token token_id = common_sampler_sample(sampler_, lctx_, -1);
            generated_tokens.push_back(token_id);
            common_sampler_accept(sampler_, token_id, true);
            if (llama_vocab_is_eog(llama_model_get_vocab(model_), token_id)) {
                break;
            }
            common_batch_clear(generation_batch_);
            common_batch_add(generation_batch_, token_id, new_n_past++, {0}, true);
            if (llama_decode(lctx_, generation_batch_) != 0) {
                LOGe("llama_decode failed during generation");
                break;
            }
        }

        const auto response = common_detokenize(lctx_, generated_tokens);
        LOGi("Multimodal analyze produced %zu tokens and %zu chars", generated_tokens.size(), response.size());
        return response;
    }

    void unload() {
        std::lock_guard<std::mutex> lock(mutex_);
        unloadLocked();
    }

    void shutdown() {
        std::lock_guard<std::mutex> lock(mutex_);
        unloadLocked();
        llama_backend_free();
    }

private:
    void unloadLocked() {
        loaded_ = false;
        if (generation_batch_.token) {
            llama_batch_free(generation_batch_);
            generation_batch_ = llama_batch {};
        }
        tmpls_.reset();
        if (sampler_ != nullptr) {
            common_sampler_free(sampler_);
            sampler_ = nullptr;
        }
        if (mtmd_ != nullptr) {
            mtmd_free(mtmd_);
            mtmd_ = nullptr;
        }
        if (lctx_ != nullptr) {
            llama_free(lctx_);
            lctx_ = nullptr;
        }
        if (model_ != nullptr) {
            llama_model_free(model_);
            model_ = nullptr;
        }
    }

    std::mutex mutex_;
    bool loaded_ = false;
    llama_model * model_ = nullptr;
    llama_context * lctx_ = nullptr;
    mtmd_context * mtmd_ = nullptr;
    common_sampler * sampler_ = nullptr;
    common_chat_templates_ptr tmpls_;
    llama_batch generation_batch_ = {};
};

MultimodalEngine & engine() {
    static MultimodalEngine instance;
    return instance;
}

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_MultimodalEngineImpl_init(JNIEnv * env, jobject, jstring native_lib_dir) {
    engine().init(env, native_lib_dir);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_MultimodalEngineImpl_load(JNIEnv * env, jobject, jstring model_path, jstring mmproj_path) {
    return engine().load(env, model_path, mmproj_path);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_MultimodalEngineImpl_nativeAnalyzeImage(JNIEnv * env, jobject, jstring prompt, jstring image_path, jint predict_length) {
    const auto text = engine().analyze(env, prompt, image_path, predict_length);
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_MultimodalEngineImpl_unload(JNIEnv *, jobject) {
    engine().unload();
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_MultimodalEngineImpl_shutdown(JNIEnv *, jobject) {
    engine().shutdown();
}
