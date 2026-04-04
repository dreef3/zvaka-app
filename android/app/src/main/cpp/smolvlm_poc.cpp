#include <android/log.h>
#include <jni.h>

#include <sstream>
#include <string>

namespace {

constexpr const char* kTag = "SmolVlmNative";

std::string JStringToStdString(JNIEnv* env, jstring value) {
  if (value == nullptr) {
    return "";
  }
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string result = chars == nullptr ? "" : chars;
  if (chars != nullptr) {
    env->ReleaseStringUTFChars(value, chars);
  }
  return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_dreef3_weightlossapp_inference_SmolVlmNativeBridge_runSamplePoc(
    JNIEnv* env,
    jobject /* this */,
    jstring model_path,
    jstring image_path,
    jstring prompt) {
  const std::string model = JStringToStdString(env, model_path);
  const std::string image = JStringToStdString(env, image_path);
  const std::string prompt_text = JStringToStdString(env, prompt);

  __android_log_print(
      ANDROID_LOG_INFO,
      kTag,
      "Native SmolVLM PoC invoked model=%s image=%s prompt=%s",
      model.c_str(),
      image.c_str(),
      prompt_text.c_str());

  std::ostringstream out;
  out << "native_poc_ready|model=" << model << "|image=" << image
      << "|prompt=" << prompt_text;
  const std::string message = out.str();
  return env->NewStringUTF(message.c_str());
}
