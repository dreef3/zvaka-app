/*
 * neuron_shim.c — NeuronCompilation_getSupportedOperations interposer for MT6985 v9_0_3.
 *
 * Problem:
 *   NeuronAdapter v9_0_3 NeuronCompilation_getSupportedOperations wrongly returns
 *   true for RESHAPE ops whose data input (inputs[0]) is INT32 (NNAPI operandCode 6).
 *   MDLA rejects them: NeuronCompilation_finish returns error 6 (NoExecPlan).
 *
 * Fix:
 *   Intercept NeuronCompilation_getSupportedOperations and force supported[i]=false
 *   for RESHAPE ops (type 3) whose first input is INT32 (operandCode 6).
 *   Those ops are then left as plain TFLite RESHAPE ops → CPU execution at inference.
 *
 *   To know operand types and op list at getSupportedOps call time, the shim also
 *   intercepts NeuronModel_addOperand / NeuronModel_addOperation (to track per-model
 *   state) and NeuronCompilation_createWithOptions (to link compilations → models).
 *
 * Build (on GCP VM, after renaming original):
 *   SDK_LIB=~/src/litert-build/.venv-litert-214/lib/python3.12/site-packages/ai_edge_litert_sdk_mediatek/data/v9_0_3/host/lib
 *   cp $SDK_LIB/libneuron_adapter.so $SDK_LIB/libneuron_adapter_real.so
 *   ln -sf libneuron_adapter_real.so $SDK_LIB/libneuron_adapter.so.9
 *   gcc -shared -fPIC -o $SDK_LIB/libneuron_adapter.so neuron_shim.c \
 *       -L$SDK_LIB -Wl,--no-as-needed -lneuron_adapter_real -Wl,--as-needed \
 *       -Wl,-rpath,'$ORIGIN' -ldl
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>

/* ── NNAPI constants ─────────────────────────────────────────────────────── */
#define NNAPI_RESHAPE          22       /* OperationType ANEURALNETWORKS_RESHAPE */
#define NNAPI_INT32             4       /* OperandCode ANEURALNETWORKS_TENSOR_INT32 */
/* NNAPI extension op base (vendor-specific fused ops, e.g. MediaTek RMSNorm).
 * NeuronAdapter v9_0_3 marks these as supported but then inlines them into
 * TRANSPOSE+MEAN primitives that individually fail the second partition pass,
 * yielding "selected 0 ops".  Force them to CPU instead. */
#define NNAPI_EXTENSION_BASE  0x10000  /* = 65536 */

typedef struct NeuronModel       NeuronModel;
typedef struct NeuronCompilation NeuronCompilation;

/* Simplified NeuronOperandType (host-side) */
typedef struct {
    int32_t       type;            /* operandCode */
    uint32_t      dimensionCount;
    const uint32_t *dimensions;
    float         scale;
    int32_t       zeroPoint;
} NeuronOperandType;

/* Write diagnostics to a dedicated log file to survive fd redirections */
static FILE *g_log = NULL;
static FILE *shim_log(void) {
    if (!g_log) g_log = fopen("/tmp/neuron_shim_debug.log", "a");
    return g_log ? g_log : stderr;
}

/* ── Pre-loaded real function pointers (set in constructor) ──────────────── */
static int  (*real_NeuronModel_addOperand)(NeuronModel *, const NeuronOperandType *)     = NULL;
static int  (*real_NeuronModel_addOperation)(NeuronModel *, int32_t, uint32_t,
             const uint32_t *, uint32_t, const uint32_t *)                                = NULL;
static void (*real_NeuronModel_free)(NeuronModel *)                                       = NULL;
static int  (*real_NeuronCompilation_create)(NeuronModel *, NeuronCompilation **)         = NULL;
static int  (*real_NeuronCompilation_createWithOptions)(NeuronModel *,
             NeuronCompilation **, const char *)                                           = NULL;
static void (*real_NeuronCompilation_free)(NeuronCompilation *)                           = NULL;
static int  (*real_NeuronCompilation_getSupportedOperations)(NeuronCompilation *, uint32_t, bool *) = NULL;
static int  (*real_NeuronCompilation_finish)(NeuronCompilation *)                                    = NULL;
static int  (*real_NeuronCompilation_getCompiledNetworkSize)(NeuronCompilation *, size_t *)          = NULL;
static int  (*real_NeuronCompilation_storeCompiledNetwork)(NeuronCompilation *, void *, size_t)      = NULL;

__attribute__((constructor))
static void shim_init(void) {
    /* RTLD_NEXT finds symbols past this library in the link map — the real lib
     * is our NEEDED dependency (libneuron_adapter_real.so), so it comes next. */
    real_NeuronModel_addOperand =
        dlsym(RTLD_NEXT, "NeuronModel_addOperand");
    real_NeuronModel_addOperation =
        dlsym(RTLD_NEXT, "NeuronModel_addOperation");
    real_NeuronModel_free =
        dlsym(RTLD_NEXT, "NeuronModel_free");
    real_NeuronCompilation_create =
        dlsym(RTLD_NEXT, "NeuronCompilation_create");
    real_NeuronCompilation_createWithOptions =
        dlsym(RTLD_NEXT, "NeuronCompilation_createWithOptions");
    real_NeuronCompilation_free =
        dlsym(RTLD_NEXT, "NeuronCompilation_free");
    real_NeuronCompilation_getSupportedOperations =
        dlsym(RTLD_NEXT, "NeuronCompilation_getSupportedOperations");
    real_NeuronCompilation_finish =
        dlsym(RTLD_NEXT, "NeuronCompilation_finish");
    real_NeuronCompilation_getCompiledNetworkSize =
        dlsym(RTLD_NEXT, "NeuronCompilation_getCompiledNetworkSize");
    real_NeuronCompilation_storeCompiledNetwork =
        dlsym(RTLD_NEXT, "NeuronCompilation_storeCompiledNetwork");

    /* Fallback: if RTLD_NEXT failed (dlopen'd context), try the real lib directly. */
    if (!real_NeuronModel_addOperand) {
        void *h = dlopen("libneuron_adapter_real.so", RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
        if (!h) h = dlopen("libneuron_adapter_real.so", RTLD_NOW | RTLD_LOCAL);
        if (h) {
            real_NeuronModel_addOperand             = dlsym(h, "NeuronModel_addOperand");
            real_NeuronModel_addOperation           = dlsym(h, "NeuronModel_addOperation");
            real_NeuronModel_free                   = dlsym(h, "NeuronModel_free");
            real_NeuronCompilation_create           = dlsym(h, "NeuronCompilation_create");
            real_NeuronCompilation_createWithOptions= dlsym(h, "NeuronCompilation_createWithOptions");
            real_NeuronCompilation_free             = dlsym(h, "NeuronCompilation_free");
            real_NeuronCompilation_getSupportedOperations =
                dlsym(h, "NeuronCompilation_getSupportedOperations");
            real_NeuronCompilation_finish =
                dlsym(h, "NeuronCompilation_finish");
            real_NeuronCompilation_getCompiledNetworkSize =
                dlsym(h, "NeuronCompilation_getCompiledNetworkSize");
            real_NeuronCompilation_storeCompiledNetwork =
                dlsym(h, "NeuronCompilation_storeCompiledNetwork");
            fprintf(stderr, "[neuron_shim] init via dlopen fallback\n");
        } else {
            fprintf(stderr, "[neuron_shim] ERROR: could not load libneuron_adapter_real.so: %s\n",
                    dlerror());
        }
    }

    fprintf(stderr,
            "[neuron_shim] init: addOperand=%p addOp=%p createWithOpts=%p getSupportedOps=%p\n",
            (void *)real_NeuronModel_addOperand,
            (void *)real_NeuronModel_addOperation,
            (void *)real_NeuronCompilation_createWithOptions,
            (void *)real_NeuronCompilation_getSupportedOperations);
}

/* ── Per-model state ─────────────────────────────────────────────────────── */
#define MAX_MODELS    32
#define MAX_OPERANDS  8192
#define MAX_OPS       2048
#define MAX_OP_INPUTS 16

typedef struct {
    NeuronModel *model;
    int          operand_count;
    int32_t      operand_type[MAX_OPERANDS];
    int          op_count;
    struct {
        int32_t  type;
        uint32_t n_inputs;
        uint32_t inputs[MAX_OP_INPUTS];
    } ops[MAX_OPS];
} ModelState;

static ModelState g_models[MAX_MODELS];
static int        g_model_count = 0;

static ModelState *find_model(NeuronModel *m) {
    for (int i = 0; i < g_model_count; i++)
        if (g_models[i].model == m) return &g_models[i];
    return NULL;
}
static ModelState *find_or_create_model(NeuronModel *m) {
    ModelState *s = find_model(m);
    if (s) return s;
    if (g_model_count >= MAX_MODELS) return NULL;
    s = &g_models[g_model_count++];
    memset(s, 0, sizeof(*s));
    s->model = m;
    return s;
}

/* ── Per-compilation → model mapping ─────────────────────────────────────── */
#define MAX_COMPILATIONS 64

typedef struct {
    NeuronCompilation *compilation;
    NeuronModel       *model;
} CompState;

static CompState g_compilations[MAX_COMPILATIONS];
static int       g_comp_count = 0;

static void comp_register(NeuronCompilation *c, NeuronModel *m) {
    if (g_comp_count >= MAX_COMPILATIONS) return;
    g_compilations[g_comp_count].compilation = c;
    g_compilations[g_comp_count].model       = m;
    g_comp_count++;
}
static NeuronModel *comp_find_model(NeuronCompilation *c) {
    for (int i = 0; i < g_comp_count; i++)
        if (g_compilations[i].compilation == c) return g_compilations[i].model;
    return NULL;
}
static void comp_remove(NeuronCompilation *c) {
    for (int i = 0; i < g_comp_count; i++) {
        if (g_compilations[i].compilation == c) {
            g_compilations[i] = g_compilations[--g_comp_count];
            return;
        }
    }
}

/* ── Fake-compilation tracking ────────────────────────────────────────────── */
#define MAX_FAKE 64
static NeuronCompilation *g_fake_comp[MAX_FAKE];
static int                g_fake_count = 0;

static bool fake_is(NeuronCompilation *c) {
    for (int i = 0; i < g_fake_count; i++)
        if (g_fake_comp[i] == c) return true;
    return false;
}
static void fake_mark(NeuronCompilation *c) {
    if (g_fake_count < MAX_FAKE) g_fake_comp[g_fake_count++] = c;
}
static void fake_unmark(NeuronCompilation *c) {
    for (int i = 0; i < g_fake_count; i++) {
        if (g_fake_comp[i] == c) {
            g_fake_comp[i] = g_fake_comp[--g_fake_count];
            return;
        }
    }
}

/* ── Intercepted functions ────────────────────────────────────────────────── */

int NeuronModel_addOperand(NeuronModel *model, const NeuronOperandType *type) {
    fprintf(stderr, "[neuron_shim] addOperand model=%p\n", (void *)model);
    int rc = real_NeuronModel_addOperand ? real_NeuronModel_addOperand(model, type) : -1;
    if (rc == 0) {
        ModelState *s = find_or_create_model(model);
        if (s && s->operand_count < MAX_OPERANDS)
            s->operand_type[s->operand_count++] = type ? type->type : -1;
    }
    return rc;
}

int NeuronModel_addOperation(NeuronModel *model,
                              int32_t      op_type,
                              uint32_t     inputCount,
                              const uint32_t *inputs,
                              uint32_t     outputCount,
                              const uint32_t *outputs) {
    fprintf(stderr, "[neuron_shim] addOperation model=%p type=%d\n", (void *)model, op_type);
    int rc = real_NeuronModel_addOperation
             ? real_NeuronModel_addOperation(model, op_type, inputCount, inputs,
                                             outputCount, outputs)
             : -1;
    if (rc == 0) {
        ModelState *s = find_or_create_model(model);
        if (s && s->op_count < MAX_OPS) {
            typeof(s->ops[0]) *op = &s->ops[s->op_count++];
            op->type     = op_type;
            op->n_inputs = inputCount < MAX_OP_INPUTS ? inputCount : MAX_OP_INPUTS;
            if (inputs)
                memcpy(op->inputs, inputs, op->n_inputs * sizeof(uint32_t));
        }
    }
    return rc;
}

void NeuronModel_free(NeuronModel *model) {
    fprintf(stderr, "[neuron_shim] NeuronModel_free model=%p\n", (void *)model);
    for (int i = 0; i < g_model_count; i++) {
        if (g_models[i].model == model) {
            g_models[i] = g_models[--g_model_count];
            break;
        }
    }
    if (real_NeuronModel_free) real_NeuronModel_free(model);
}

int NeuronCompilation_create(NeuronModel *model, NeuronCompilation **compilation) {
    fprintf(stderr, "[neuron_shim] NeuronCompilation_create model=%p\n", (void *)model);
    int rc = real_NeuronCompilation_create
             ? real_NeuronCompilation_create(model, compilation)
             : -1;
    if (rc == 0 && compilation && *compilation)
        comp_register(*compilation, model);
    return rc;
}

/* Plugin uses createWithOptions in practice.
 * Real signature (determined by diagnostic): (model, compilation**, options) */
int NeuronCompilation_createWithOptions(NeuronModel *model,
                                         NeuronCompilation **compilation,
                                         const char  *options) {
    fprintf(stderr, "[neuron_shim] createWithOptions model=%p opts=%s\n",
            (void *)model, options ? options : "(null)");
    int rc = real_NeuronCompilation_createWithOptions
             ? real_NeuronCompilation_createWithOptions(model, compilation, options)
             : -1;
    if (rc == 0 && compilation && *compilation) {
        comp_register(*compilation, model);
        fprintf(stderr, "[neuron_shim] createWithOptions → comp=%p registered (model_count=%d op_count=%d)\n",
                (void *)*compilation, g_model_count,
                find_model(model) ? find_model(model)->op_count : -1);
    } else {
        fprintf(stderr, "[neuron_shim] createWithOptions FAILED rc=%d\n", rc);
    }
    return rc;
}

void NeuronCompilation_free(NeuronCompilation *compilation) {
    fprintf(stderr, "[neuron_shim] NeuronCompilation_free comp=%p\n", (void *)compilation);
    comp_remove(compilation);
    fake_unmark(compilation);
    if (real_NeuronCompilation_free) real_NeuronCompilation_free(compilation);
}

/* When NeuronCompilation_finish fails (MDLA rejects a subgraph), we return 0
 * (fake success) so apply_plugin doesn't abort the whole compilation.  We mark
 * the compilation as "fake".  Subsequent getCompiledNetworkSize → 0 bytes and
 * storeCompiledNetwork → no-op so the compiled TFLite has an empty DLA entry
 * for that subgraph.  LiteRT then runs those ops via the CPU delegate at
 * inference time. */
int NeuronCompilation_finish(NeuronCompilation *compilation) {
    FILE *L = shim_log();
    NeuronModel *m    = comp_find_model(compilation);
    ModelState  *s    = m ? find_model(m) : NULL;
    int          nops = s ? s->op_count : -1;
    int rc = real_NeuronCompilation_finish
             ? real_NeuronCompilation_finish(compilation) : -1;
    if (rc != 0) {
        fprintf(L,
                "[neuron_shim] NeuronCompilation_finish FAILED rc=%d nops=%d → fake success\n",
                rc, nops);
        fprintf(stderr,
                "[neuron_shim] NeuronCompilation_finish FAILED rc=%d nops=%d → fake success\n",
                rc, nops);
        if (s) {
            fprintf(L, "  op_types:");
            for (int i = 0; i < s->op_count && i < 32; i++)
                fprintf(L, " %d", s->ops[i].type);
            fprintf(L, "\n");
        }
        fflush(L);
        fake_mark(compilation);
        return 0;
    }
    fprintf(L, "[neuron_shim] NeuronCompilation_finish OK nops=%d\n", nops);
    fflush(L);
    return 0;
}

int NeuronCompilation_getCompiledNetworkSize(NeuronCompilation *compilation, size_t *size) {
    FILE *L = shim_log();
    if (fake_is(compilation)) {
        if (size) *size = 0;
        fprintf(L, "[neuron_shim] getCompiledNetworkSize: fake comp → 0 bytes\n");
        fflush(L);
        return 0;
    }
    return real_NeuronCompilation_getCompiledNetworkSize
           ? real_NeuronCompilation_getCompiledNetworkSize(compilation, size) : -1;
}

int NeuronCompilation_storeCompiledNetwork(NeuronCompilation *compilation,
                                            void *buffer, size_t size) {
    FILE *L = shim_log();
    if (fake_is(compilation)) {
        fprintf(L, "[neuron_shim] storeCompiledNetwork: fake comp → no-op (size=%zu)\n", size);
        fflush(L);
        return 0;
    }
    return real_NeuronCompilation_storeCompiledNetwork
           ? real_NeuronCompilation_storeCompiledNetwork(compilation, buffer, size) : -1;
}

/* Real signature (confirmed by diagnostic): 3-arg (compilation, numOps, supported[]).
 * 0x34c = 844 was received as 'supported' when using 2-arg — that was numOps passing
 * in place of supported*.
 *
 * The real NeuronCompilation_getSupportedOperations internally calls exit() after
 * completing the NIR analysis, so any code placed after the call never runs.
 * Fix: fork a child to call the real function; use a MAP_SHARED mmap as the
 * output buffer so the child's writes are immediately visible to the parent.
 * The parent waits for the child, patches INT32-RESHAPE ops out, and returns. */
int NeuronCompilation_getSupportedOperations(NeuronCompilation *compilation,
                                              uint32_t           numOps,
                                              bool              *supported) {
    FILE *L = shim_log();
    fprintf(stderr, "[neuron_shim] getSupportedOps comp=%p numOps=%u\n",
            (void *)compilation, numOps);
    fprintf(L, "[neuron_shim] getSupportedOps ENTER comp=%p numOps=%u\n",
            (void *)compilation, numOps);
    fflush(L);

    if (!real_NeuronCompilation_getSupportedOperations || !supported || numOps == 0) return -1;

    NeuronModel *m = comp_find_model(compilation);
    ModelState  *s = m ? find_model(m) : NULL;
    int num_ops = s ? s->op_count : 0;

    if (num_ops <= 0) {
        fprintf(L, "[neuron_shim] getSupportedOps: no ops tracked, falling back to direct call\n");
        fflush(L);
        return real_NeuronCompilation_getSupportedOperations(compilation, numOps, supported);
    }

    /* Allocate shared anonymous mmap so child writes survive child exit().
     * Use numOps (caller-authoritative) for the buffer, plus 1 for the done flag. */
    uint32_t buf_count = numOps > (uint32_t)num_ops ? numOps : (uint32_t)num_ops;
    size_t mmap_sz = (size_t)(buf_count + 1) * sizeof(bool);
    bool *shared = mmap(NULL, mmap_sz, PROT_READ | PROT_WRITE,
                        MAP_SHARED | MAP_ANONYMOUS, -1, 0);
    if (shared == MAP_FAILED) {
        fprintf(L, "[neuron_shim] getSupportedOps: mmap failed, direct call\n"); fflush(L);
        return real_NeuronCompilation_getSupportedOperations(compilation, numOps, supported);
    }
    memset(shared, 0, mmap_sz);

    pid_t child = fork();
    if (child == 0) {
        /* ── child ── call real fn with shared buffer, then _Exit regardless ── */
        real_NeuronCompilation_getSupportedOperations(compilation, numOps, shared);
        shared[buf_count] = 1; /* "done" flag: function returned normally */
        _Exit(0);
    } else if (child > 0) {
        /* ── parent ── wait for child, read result ── */
        int wstatus = 0;
        waitpid(child, &wstatus, 0);

        bool done_flag   = shared[buf_count];
        bool child_ok    = WIFEXITED(wstatus);  /* false → killed by signal */
        int  child_sig   = child_ok ? 0 : WTERMSIG(wstatus);

        fprintf(L,
                "[neuron_shim] getSupportedOps: child exited=%d status=%d sig=%d done_flag=%d\n",
                child_ok,
                child_ok ? WEXITSTATUS(wstatus) : -1,
                child_sig, (int)done_flag);
        fflush(L);

        /* NPU whitelist: op types known to compile on MT6985 MDLA.
         * Used when the child crashes (signal) OR returns all-false (silent model-level
         * validation failure, e.g. FC bias check in v8_0_10 causes getSupportedOperations
         * to return 0 ops for the whole model without crashing). */
        static const int32_t npu_whitelist[] = {
            0,   /* ADD */
            2,   /* CONCATENATION */
            /* 9 = FULLY_CONNECTED excluded: FC bias check ("Bias should be floating
             *   point type") makes MDLA reject the whole subgraph.  CPU instead. */
            /* 18 = MUL excluded: MUL+CONCAT subgraphs fail NeuronCompilation_finish
             *   with NoExecPlan (error 6); fake-success 0-byte DLA blobs crash
             *   NeuronCompilation_restoreFromCompiledNetwork at runtime. */
            /* 22 = RESHAPE excluded: MDLA rejects 2-input RESHAPE regardless of data
             *   type — both INT8 and INT32 inputs cause NoExecPlan at finish time. */
            25,  /* SOFTMAX */
            31,  /* MEAN */
            36,  /* SUB */
            37,  /* TRANSPOSE */
            53,  /* GREATER */
            86,  /* BATCH_MATMUL */
        };
        static const int npu_wl_sz =
            (int)(sizeof(npu_whitelist) / sizeof(npu_whitelist[0]));

        bool use_whitelist = false;

        if (child_ok) {
            /* Normal case: real function filled shared[] before calling exit().
             * The data is valid even when WEXITSTATUS is non-zero. */
            memcpy(supported, shared, (size_t)numOps * sizeof(bool));

            /* Detect silent all-zero result: real getSupportedOperations may return
             * 0 ops via clean exit (no crash) due to model-level validation failures,
             * e.g. v8_0_10 FC bias check fires for INT32 biases and causes the whole
             * getSupportedOperations to return false for every op.
             * Fall back to whitelist rather than returning 0 ops to the caller. */
            bool any_true = false;
            for (uint32_t i = 0; i < numOps; i++) {
                if (supported[i]) { any_true = true; break; }
            }
            if (!any_true && num_ops > 0) {
                use_whitelist = true;
                fprintf(L,
                        "[neuron_shim] getSupportedOps: child all-zero (silent fail) — whitelist fallback\n");
                fflush(L);
            }
        } else {
            /* Child was killed by signal — real getSupportedOperations crashed. */
            use_whitelist = true;
            fprintf(L,
                    "[neuron_shim] getSupportedOps: child SIG%d — whitelist fallback (%d types)\n",
                    child_sig, npu_wl_sz);
            fflush(L);
        }

        if (use_whitelist) {
            memset(supported, 0, (size_t)numOps * sizeof(bool));
            if (s) {
                for (int i = 0; i < num_ops && i < (int)numOps; i++) {
                    typeof(s->ops[0]) *op = &s->ops[i];
                    /* MDLA cannot handle any op whose primary input is INT32 */
                    if (op->n_inputs > 0 && op->inputs[0] < (uint32_t)s->operand_count &&
                        s->operand_type[op->inputs[0]] == NNAPI_INT32) continue;
                    for (int j = 0; j < npu_wl_sz; j++) {
                        if (op->type != npu_whitelist[j]) continue;
                        supported[i] = true;
                        break;
                    }
                }
            }
        }
        munmap(shared, mmap_sz);

        /* Patch: force INT32 RESHAPE ops and extension ops to unsupported. */
        int patched_reshape = 0;
        int patched_ext     = 0;
        if (s) {
            /* Diagnostic: log first 10 ops to confirm type values */
            int diag_limit = num_ops < 10 ? num_ops : 10;
            for (int i = 0; i < diag_limit; i++) {
                typeof(s->ops[0]) *op = &s->ops[i];
                int32_t inp0_type = (op->n_inputs > 0 && op->inputs[0] < (uint32_t)s->operand_count)
                                    ? s->operand_type[op->inputs[0]] : -1;
                fprintf(L, "  op[%d] type=%d supported=%d inp0_idx=%u inp0_type=%d\n",
                        i, op->type, (int)supported[i],
                        op->n_inputs > 0 ? op->inputs[0] : 9999u, inp0_type);
            }
            fflush(L);

            for (int i = 0; i < num_ops; i++) {
                if (!supported[i]) continue;
                typeof(s->ops[0]) *op = &s->ops[i];

                /* Force extension/vendor-fused ops (type >= 0x10000) to CPU.
                 * These composite ops get inlined into TRANSPOSE+MEAN primitives
                 * that the second-pass partition rejects entirely ("selected 0 ops"). */
                if (op->type >= NNAPI_EXTENSION_BASE) {
                    supported[i] = false;
                    patched_ext++;
                    continue;
                }

                /* Force ALL RESHAPE ops to CPU.
                 * MDLA rejects 2-input RESHAPE (data + shape tensor) regardless of
                 * data type — both INT8 and INT32 inputs hit NoExecPlan at
                 * NeuronCompilation_finish time. */
                if (op->type == NNAPI_RESHAPE) {
                    supported[i] = false;
                    patched_reshape++;
                    continue;
                }

                /* Force ALL FC (FULLY_CONNECTED, type 9) ops to CPU.
                 * Consistent with whitelist fallback exclusion: MDLA rejects INT4/INT8
                 * FC ops with "data type mismatch for input and filter" at compile time,
                 * which causes NoExecPlan for any subgraph containing them. */
                if (op->type == 9 /* ANEURALNETWORKS_FULLY_CONNECTED */) {
                    supported[i] = false;
                    patched_reshape++;
                    continue;
                }
            }
        }
        fprintf(stderr,
                "[neuron_shim] getSupportedOps: scanned %d ops, forced %d RESHAPE/FC + %d EXT unsupported\n",
                num_ops, patched_reshape, patched_ext);
        fprintf(L,
                "[neuron_shim] getSupportedOps: scanned %d ops, forced %d RESHAPE/FC + %d EXT unsupported\n",
                num_ops, patched_reshape, patched_ext);
        fflush(L);
        /* Return 0 — callers must not abort compilation based on getSupportedOps rc. */
        return 0;
    } else {
        /* fork failed */
        munmap(shared, mmap_sz);
        fprintf(L, "[neuron_shim] getSupportedOps: fork failed, direct call\n"); fflush(L);
        return real_NeuronCompilation_getSupportedOperations(compilation, numOps, supported);
    }
}
