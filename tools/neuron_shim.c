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
#define NNAPI_RESHAPE   22  /* OperationType ANEURALNETWORKS_RESHAPE */
#define NNAPI_INT32     4   /* OperandCode ANEURALNETWORKS_TENSOR_INT32 */

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
    if (real_NeuronCompilation_free) real_NeuronCompilation_free(compilation);
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

        bool done_flag = shared[buf_count];

        fprintf(L,
                "[neuron_shim] getSupportedOps: child exited status=%d done_flag=%d\n",
                WIFEXITED(wstatus) ? WEXITSTATUS(wstatus) : -1, (int)done_flag);
        fflush(L);

        /* Copy result to caller's buffer regardless of child exit code —
         * the real function fills shared[] before calling exit(), so the data
         * is valid even when child_rc is non-zero. */
        fprintf(L, "[neuron_shim] getSupportedOps: BEFORE_MEMCPY supported=%p shared=%p numOps=%u num_ops=%d s=%p\n",
                (void *)supported, (void *)shared, numOps, num_ops, (void *)s); fflush(L);
        memcpy(supported, shared, (size_t)numOps * sizeof(bool));
        fprintf(L, "[neuron_shim] getSupportedOps: AFTER_MEMCPY\n"); fflush(L);
        munmap(shared, mmap_sz);
        fprintf(L, "[neuron_shim] getSupportedOps: AFTER_MUNMAP\n"); fflush(L);

        /* Patch: force INT32 RESHAPE ops to unsupported. */
        int patched = 0;
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
                if (op->type != NNAPI_RESHAPE) continue;
                if (op->n_inputs < 1) continue;
                uint32_t data_idx = op->inputs[0];
                if (data_idx >= (uint32_t)s->operand_count) continue;
                if (s->operand_type[data_idx] == NNAPI_INT32) {
                    supported[i] = false;
                    patched++;
                }
            }
        }
        fprintf(stderr,
                "[neuron_shim] getSupportedOps: scanned %d ops, forced %d INT32-RESHAPE unsupported\n",
                num_ops, patched);
        fprintf(L,
                "[neuron_shim] getSupportedOps: scanned %d ops, forced %d INT32-RESHAPE unsupported\n",
                num_ops, patched);
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
