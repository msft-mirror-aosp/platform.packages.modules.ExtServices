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

#include <ImageHashManager.h>
#include <gtest/gtest.h>

namespace android {
class ImageHashManagerTest : public ::testing::Test {};

// TODO: Add real wavelet test
TEST_F(ImageHashManagerTest, TestWavelet) {
    std::array<uint8_t, 12> buf = {1, 4, 2, 6, 1, 7, 4, 7, 3, 1, 5, 3};
    int32_t width = 3;
    int32_t height = 4;
    std::array<uint8_t, 8> imageHash;
    std::array<uint8_t, 8> expectedHash = {buf[0], buf[0], buf[0], buf[0],
                                           buf[0], buf[0], buf[0], buf[0]};

    ImageHashManager::generateWaveletHash(buf.data(), width, height, &imageHash);
    ASSERT_EQ(expectedHash, imageHash);
}

} // namespace android