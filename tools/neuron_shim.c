/*
 * neuron_shim.c — NeuronCompilation_finish interposer for MT6985 v9_0_3 AOT compile.
 *
 * Problem:
 *   NeuronAdapter v9_0_3 GetSupportedOperations wrongly returns true for RESHAPE
 *   ops with INT32 data input. MDLA rejects them: NeuronCompilation_finish returns
 *   error 6 (ANEURALNETWORKS_BAD_STATE / NoExecPlan) → apply_plugin exits non-zero.
 *
 * Fix:
 *   Replace libneuron_adapter.so with this shim. The shim:
 *   - Keeps all real Neuron* symbols via NEEDED dependency on libneuron_adapter_real.so
 *   - Intercepts NeuronCompilation_finish: converts error 6 → fake 0 (success)
 *   - Intercepts getCompiledNetworkSize: returns 0 for fake-succeeded handles
 *   - Intercepts storeCompiledNetwork: no-op for fake-succeeded handles
 *   Result: those partitions produce empty DLA blobs → CPU fallback at inference.
 *
 * Build (on GCP VM, after renaming original):
 *   SDK_LIB=~/.venv-litert-214/lib/python3.12/site-packages/ai_edge_litert_sdk_mediatek/data/v9_0_3/host/lib
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
#include <stdint.h>

typedef struct NeuronCompilation NeuronCompilation;

static void *real_lib_h = NULL;

#define MAX_FAKE 64
static void *fake_handles[MAX_FAKE];
static int   fake_count = 0;

static void fake_add(void *h) {
    if (fake_count < MAX_FAKE) fake_handles[fake_count++] = h;
}
static int fake_check(void *h) {
    for (int i = 0; i < fake_count; i++) if (fake_handles[i] == h) return 1;
    return 0;
}

__attribute__((constructor))
static void shim_init(void) {
    real_lib_h = dlopen("libneuron_adapter_real.so", RTLD_NOW | RTLD_LOCAL);
    if (!real_lib_h)
        fprintf(stderr, "[neuron_shim] WARNING: could not open libneuron_adapter_real.so: %s\n", dlerror());
    else
        fprintf(stderr, "[neuron_shim] loaded libneuron_adapter_real.so OK\n");
}

int NeuronCompilation_finish(NeuronCompilation *compilation) {
    static int (*real_fn)(NeuronCompilation *) = NULL;
    if (!real_fn && real_lib_h)
        real_fn = (int (*)(NeuronCompilation *))dlsym(real_lib_h, "NeuronCompilation_finish");
    int rc = real_fn ? real_fn(compilation) : 6;
    if (rc == 6) {
        fprintf(stderr, "[neuron_shim] NeuronCompilation_finish rc=6 → 0 (NoExecPlan, fake-success for empty DLA)\n");
        fake_add(compilation);
        return 0;
    }
    return rc;
}

int NeuronCompilation_getCompiledNetworkSize(NeuronCompilation *compilation,
                                              size_t *size) {
    if (fake_check(compilation)) {
        fprintf(stderr, "[neuron_shim] getCompiledNetworkSize: fake handle → 0 bytes\n");
        if (size) *size = 0;
        return 0;
    }
    static int (*real_fn)(NeuronCompilation *, size_t *) = NULL;
    if (!real_fn && real_lib_h)
        real_fn = (int (*)(NeuronCompilation *, size_t *))dlsym(real_lib_h, "NeuronCompilation_getCompiledNetworkSize");
    return real_fn ? real_fn(compilation, size) : -1;
}

int NeuronCompilation_storeCompiledNetwork(NeuronCompilation *compilation,
                                            void *buffer,
                                            const size_t size) {
    if (fake_check(compilation)) {
        fprintf(stderr, "[neuron_shim] storeCompiledNetwork: fake handle → no-op\n");
        return 0;
    }
    static int (*real_fn)(NeuronCompilation *, void *, const size_t) = NULL;
    if (!real_fn && real_lib_h)
        real_fn = (int (*)(NeuronCompilation *, void *, const size_t))dlsym(real_lib_h, "NeuronCompilation_storeCompiledNetwork");
    return real_fn ? real_fn(compilation, buffer, size) : -1;
}
