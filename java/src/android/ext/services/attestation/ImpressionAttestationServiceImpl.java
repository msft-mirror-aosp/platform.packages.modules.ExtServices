/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.ext.services.attestation;

import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.service.attestation.ImpressionAttestationService;
import android.service.attestation.ImpressionToken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The implementation service for {@link ImpressionAttestationService}
 */
public class ImpressionAttestationServiceImpl extends ImpressionAttestationService {
    @Override
    @Nullable
    public ImpressionToken onGenerateImpressionToken(@NonNull byte[] salt,
            @NonNull HardwareBuffer screenshot, @NonNull Rect bounds,
            @NonNull String hashAlgorithm) {
        // TODO: Implement the hashing and hmac functions
        return new ImpressionToken(System.currentTimeMillis(), bounds, hashAlgorithm,
                new byte[8], new byte[32]);
    }

    @Override
    public boolean onVerifyImpressionToken(@NonNull byte[] salt,
            @NonNull ImpressionToken impressionToken) {
        // TODO: Implement the verification
        return true;
    }
}
