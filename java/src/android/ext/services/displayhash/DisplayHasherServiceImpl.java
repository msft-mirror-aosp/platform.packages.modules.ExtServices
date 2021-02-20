/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.ext.services.displayhash;

import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.service.displayhash.DisplayHasherService;
import android.view.displayhash.DisplayHash;
import android.view.displayhash.DisplayHashResultCallback;
import android.view.displayhash.VerifiedDisplayHash;

import androidx.annotation.NonNull;

/**
 * The implementation service for {@link DisplayHasherService}
 */
public class DisplayHasherServiceImpl extends DisplayHasherService {
    @Override
    public void onGenerateDisplayHash(@NonNull byte[] salt,
            @NonNull HardwareBuffer buffer, @NonNull Rect bounds,
            @NonNull String hashAlgorithm, @NonNull DisplayHashResultCallback callback) {
        // TODO: Implement the hashing and hmac functions
        DisplayHash displayHash = new DisplayHash(System.currentTimeMillis(), bounds,
                hashAlgorithm, new byte[8], new byte[32]);
        callback.onDisplayHashResult(displayHash);
    }

    @Override
    public VerifiedDisplayHash onVerifyDisplayHash(@NonNull byte[] salt,
            @NonNull DisplayHash displayHash) {
        // TODO: Implement the verification
        return new VerifiedDisplayHash(displayHash.getTimeMillis(),
                displayHash.getBoundsInWindow(), displayHash.getHashAlgorithm(),
                displayHash.getImageHash());
    }
}
