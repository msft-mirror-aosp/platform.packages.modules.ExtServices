/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "ImageHashManager.h"
#include <errno.h>

namespace android {

int32_t ImageHashManager::generateWaveletHash(uint8_t* buf, int32_t width, int32_t height,
                                              std::array<uint8_t, 8>* outImageHash) {
    *outImageHash = {buf[0], buf[0], buf[0], buf[0], buf[0], buf[0], buf[0], buf[0]};
    return 0;
}

int32_t ImageHashManager::generateHash(std::string hashAlgorithm, uint8_t* buf,
                                       AHardwareBuffer_Desc bufferDesc, int32_t bytesPerPixel,
                                       int32_t bytesPerStride,
                                       std::array<uint8_t, 8>* outImageHash) {
    // TODO: Add real hashing algorithms here. This just calls a fake function
    // that just returns some bytes from the buffer.
    if (hashAlgorithm == "wavelet") {
        return generateWaveletHash(buf, bufferDesc.width, bufferDesc.height, outImageHash);
    }

    return -EINVAL;
}

} // namespace android