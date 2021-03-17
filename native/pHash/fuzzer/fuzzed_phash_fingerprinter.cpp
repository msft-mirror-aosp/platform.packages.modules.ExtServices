#include <stddef.h>
#include <stdint.h>

#include "fuzzer/FuzzedDataProvider.h"
#include "pHash/phash_config.h"
#include "pHash/phash_fingerprinter.h"

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    FuzzedDataProvider fdp = FuzzedDataProvider(data, size);
    std::vector<uint8_t> buffer = fdp.ConsumeBytes<uint8_t>(android::kImageLength);
    buffer.resize(android::kImageLength);

    android::PhashFingerprinter fingerprinter;
    fingerprinter.GenerateFingerprint(buffer.data());
    return 0;
}
