/**
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package android.ext.services.notification

import android.icu.util.ULocale
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage
import android.view.textclassifier.TextLinks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito

@RunWith(AndroidJUnit4::class)
class LegacyOtpDetectionHelperTest {
  private val localeWithRegex = ULocale.ENGLISH
  private val invalidLocale = ULocale.ROOT

  private data class TestResult(
    val expected: Boolean,
    val actual: Boolean,
    val failureMessage: String,
  )

  private val results = mutableListOf<TestResult>()

  @Before
  fun enableFlag() {
    assumeTrue(SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
    results.clear()
  }

  @After
  fun verifyResults() {
    val allFailuresMessage = StringBuilder("")
    var numFailures = 0
    for ((expected, actual, failureMessage) in results) {
      if (expected != actual) {
        numFailures += 1
        allFailuresMessage.append("$failureMessage\n")
      }
    }
    assertWithMessage("Found $numFailures failures:\n$allFailuresMessage")
      .that(numFailures)
      .isEqualTo(0)
  }

  private fun addResult(expected: Boolean, actual: Boolean, failureMessage: String) {
    results.add(TestResult(expected, actual, failureMessage))
  }

  @Test
  fun testContainsOtp_length() {
    val tooShortAlphaNum = "123G"
    val tooShortNumOnly = "123"
    val minLenAlphaNum = "123G5"
    val minLenNumOnly = "1235"
    val twoTriplets = "123 456"
    val tooShortTriplets = "12 345"
    val maxLen = "123456F8"
    val tooLong = "123T56789"

    addMatcherTestResult(expected = true, minLenAlphaNum)
    addMatcherTestResult(expected = true, minLenNumOnly)
    addMatcherTestResult(expected = true, maxLen)
    addMatcherTestResult(expected = false, tooShortAlphaNum, customFailureMessage = "is too short")
    addMatcherTestResult(expected = false, tooShortNumOnly, customFailureMessage = "is too short")
    addMatcherTestResult(expected = false, tooLong, customFailureMessage = "is too long")
    addMatcherTestResult(expected = true, twoTriplets)
    addMatcherTestResult(expected = false, tooShortTriplets, customFailureMessage = "is too short")
  }

  @Test
  fun testContainsOtp_acceptsNonRomanAlphabeticalChars() {
    val lowercase = "123ķ4"
    val uppercase = "123Ŀ4"
    val ideographicInMiddle = "123码456"
    addMatcherTestResult(expected = true, lowercase)
    addMatcherTestResult(expected = true, uppercase)
    addMatcherTestResult(expected = false, ideographicInMiddle)
  }

  @Test
  fun testContainsOtp_mustHaveNumber() {
    val noNums = "TEFHXES"
    addMatcherTestResult(expected = false, noNums)
  }

  @Test
  fun testContainsOtp_dateExclusion() {
    val date = "01-01-2001"
    val singleDigitDate = "1-1-2001"
    val twoDigitYear = "1-1-01"
    val dateWithOtpAfter = "1-1-01 is the date of your code T3425"
    val dateWithOtpBefore = "your code 54-234-3 was sent on 1-1-01"
    val otpWithDashesButInvalidDate = "34-58-30"
    val otpWithDashesButInvalidYear = "12-1-3089"

    addMatcherTestResult(
      expected = true,
      date,
      checkForFalsePositives = false,
      customFailureMessage = "should match if checkForFalsePositives is false",
    )
    addMatcherTestResult(
      expected = false,
      date,
      customFailureMessage = "should not match if checkForFalsePositives is true",
    )
    addMatcherTestResult(expected = false, singleDigitDate)
    addMatcherTestResult(expected = false, twoDigitYear)
    addMatcherTestResult(expected = true, dateWithOtpAfter)
    addMatcherTestResult(expected = true, dateWithOtpBefore)
    addMatcherTestResult(expected = true, otpWithDashesButInvalidDate)
    addMatcherTestResult(expected = true, otpWithDashesButInvalidYear)
  }

  @Test
  fun testContainsOtp_phoneExclusion() {
    val parens = "(888) 8888888"
    val allSpaces = "888 888 8888"
    val withDash = "(888) 888-8888"
    val allDashes = "888-888-8888"
    val allDashesWithParen = "(888)-888-8888"
    addMatcherTestResult(
      expected = true,
      parens,
      checkForFalsePositives = false,
      customFailureMessage = "should match if checkForFalsePositives is false",
    )
    addMatcherTestResult(expected = false, parens)
    addMatcherTestResult(expected = false, allSpaces)
    addMatcherTestResult(expected = false, withDash)
    addMatcherTestResult(expected = false, allDashes)
    addMatcherTestResult(expected = false, allDashesWithParen)
  }

  @Test
  fun testContainsOtp_dashes() {
    val oneDash = "G-3d523"
    val manyDashes = "G-FD-745"
    val tooManyDashes = "6--7893"
    val oopsAllDashes = "------"
    addMatcherTestResult(expected = true, oneDash)
    addMatcherTestResult(expected = true, manyDashes)
    addMatcherTestResult(expected = false, tooManyDashes)
    addMatcherTestResult(expected = false, oopsAllDashes)
  }

  @Test
  fun testContainsOtp_startAndEnd() {
    val noSpaceStart = "your code isG-345821"
    val noSpaceEnd = "your code is G-345821for real"
    val numberSpaceStart = "your code is 4 G-345821"
    val numberSpaceEnd = "your code is G-345821 3"
    val colonStart = "your code is:G-345821"
    val newLineStart = "your code is \nG-345821"
    val quote = "your code is 'G-345821'"
    val doubleQuote = "your code is \"G-345821\""
    val bracketStart = "your code is [G-345821"
    val ideographicStart = "your code is码G-345821"
    val colonStartNumberPreceding = "your code is4:G-345821"
    val periodEnd = "you code is G-345821."
    val parens = "you code is (G-345821)"
    val squareBrkt = "you code is [G-345821]"
    val dashEnd = "you code is 'G-345821-'"
    val randomSymbolEnd = "your code is G-345821$"
    val underscoreEnd = "you code is 'G-345821_'"
    val ideographicEnd = "your code is码G-345821码"
    addMatcherTestResult(expected = false, noSpaceStart)
    addMatcherTestResult(expected = false, noSpaceEnd)
    addMatcherTestResult(expected = false, numberSpaceStart)
    addMatcherTestResult(expected = false, numberSpaceEnd)
    addMatcherTestResult(expected = false, colonStartNumberPreceding)
    addMatcherTestResult(expected = false, dashEnd)
    addMatcherTestResult(expected = false, underscoreEnd)
    addMatcherTestResult(expected = false, randomSymbolEnd)
    addMatcherTestResult(expected = true, colonStart)
    addMatcherTestResult(expected = true, newLineStart)
    addMatcherTestResult(expected = true, quote)
    addMatcherTestResult(expected = true, doubleQuote)
    addMatcherTestResult(expected = true, bracketStart)
    addMatcherTestResult(expected = true, ideographicStart)
    addMatcherTestResult(expected = true, periodEnd)
    addMatcherTestResult(expected = true, parens)
    addMatcherTestResult(expected = true, squareBrkt)
    addMatcherTestResult(expected = true, ideographicEnd)
  }

  @Test
  fun testContainsOtp_lookaheadMustBeOtpChar() {
    val validLookahead = "g4zy75"
    val spaceLookahead = "GVRXY 2"
    addMatcherTestResult(expected = true, validLookahead)
    addMatcherTestResult(expected = false, spaceLookahead)
  }

  @Test
  fun testContainsOtp_threeDontMatch_withoutLanguageSpecificRegex() {
    val tc = getTestTextClassifier(invalidLocale)
    val threeLowercase = "34agb"
    addMatcherTestResult(expected = false, threeLowercase, textClassifier = tc)
  }

  @Test
  fun testContainsOtpCode_falseIfNoLanguageSpecificRegex() {
    val tc = getTestTextClassifier(invalidLocale)
    val text = "your one time code is 34343"
    addMatcherTestResult(expected = false, text, textClassifier = tc)
  }

  @Test
  fun testContainsOtp_englishSpecificRegex() {
    val tc = getTestTextClassifier(ULocale.ENGLISH)
    val englishFalsePositive = "This is a false positive 4543"
    val englishContextWords =
      listOf(
        "login",
        "log in",
        "2fa",
        "authenticate",
        "auth",
        "authentication",
        "tan",
        "password",
        "passcode",
        "two factor",
        "two-factor",
        "2factor",
        "2 factor",
        "pin",
        "one time",
      )
    val englishContextWordsCase = listOf("LOGIN", "logIn", "LoGiN")
    // Strings with a context word somewhere in the substring
    val englishContextSubstrings = listOf("pins", "gaping", "backspin")
    val codeInSentenceAfterNewline = "your code is \n 34343"

    addMatcherTestResult(expected = false, englishFalsePositive, textClassifier = tc)
    for (context in englishContextWords) {
      val englishTruePositive = "$context $englishFalsePositive"
      addMatcherTestResult(expected = true, englishTruePositive, textClassifier = tc)
    }
    for (context in englishContextWordsCase) {
      val englishTruePositive = "$context $englishFalsePositive"
      addMatcherTestResult(expected = true, englishTruePositive, textClassifier = tc)
    }
    for (falseContext in englishContextSubstrings) {
      val anotherFalsePositive = "$falseContext $englishFalsePositive"
      addMatcherTestResult(expected = false, anotherFalsePositive, textClassifier = tc)
    }
    addMatcherTestResult(expected = true, codeInSentenceAfterNewline, textClassifier = tc)
  }

  @Test
  fun testContainsOtp_multipleFalsePositives() {
    val otp = "code 1543 code"
    val longFp = "888-777-6666"
    val shortFp = "34ess"
    val multipleLongFp = "$longFp something something $longFp"
    val multipleLongFpWithOtpBefore = "$otp $multipleLongFp"
    val multipleLongFpWithOtpAfter = "$multipleLongFp $otp"
    val multipleLongFpWithOtpBetween = "$longFp $otp $longFp"
    val multipleShortFp = "$shortFp something something $shortFp"
    val multipleShortFpWithOtpBefore = "$otp $multipleShortFp"
    val multipleShortFpWithOtpAfter = "$otp $multipleShortFp"
    val multipleShortFpWithOtpBetween = "$shortFp $otp $shortFp"
    addMatcherTestResult(expected = false, multipleLongFp)
    addMatcherTestResult(expected = false, multipleShortFp)
    addMatcherTestResult(expected = true, multipleLongFpWithOtpBefore)
    addMatcherTestResult(expected = true, multipleLongFpWithOtpAfter)
    addMatcherTestResult(expected = true, multipleLongFpWithOtpBetween)
    addMatcherTestResult(expected = true, multipleShortFpWithOtpBefore)
    addMatcherTestResult(expected = true, multipleShortFpWithOtpAfter)
    addMatcherTestResult(expected = true, multipleShortFpWithOtpBetween)
  }

  @Test
  fun testContainsOtpCode_languageSpecificOverridesFalsePositivesExceptDate() {
    // TC will detect an address, but the language-specific regex will be preferred
    val tc = getTestTextClassifier(localeWithRegex, listOf(TextClassifier.TYPE_ADDRESS))
    val date = "1-1-01"
    // Dates should still be checked
    addMatcherTestResult(expected = false, date, textClassifier = tc)
    // A string with a code with three lowercase letters, and an excluded year
    val withOtherFalsePositives = "your login code is abd4f 1985"
    // Other false positive regular expressions should not be checked
    addMatcherTestResult(expected = true, withOtherFalsePositives, textClassifier = tc)
  }

  private fun addMatcherTestResult(
    expected: Boolean,
    text: String,
    checkForFalsePositives: Boolean = true,
    textClassifier: TextClassifier? = null,
    customFailureMessage: String? = null,
  ) {
    val failureMessage =
      if (customFailureMessage != null) {
        "$text $customFailureMessage"
      } else if (expected) {
        "$text should match"
      } else {
        "$text should not match"
      }
    @Suppress("DEPRECATION") // This is mean to test the older class
    val actual = LegacyOtpDetector.containsOtp(text, checkForFalsePositives, textClassifier, null)
    addResult(expected = expected, actual, failureMessage)
  }

  // Creates a mock TextClassifier that will report back that text provided to it matches the
  // given language codes (for language requests) and textClassifier entities (for links request)
  private fun getTestTextClassifier(
    locale: ULocale?,
    tcEntities: List<String>? = null,
  ): TextClassifier {
    val tc = Mockito.mock(TextClassifier::class.java)
    if (locale != null) {
      Mockito.doReturn(TextLanguage.Builder().putLocale(locale, 0.9f).build())
        .`when`(tc)
        .detectLanguage(any(TextLanguage.Request::class.java))
    }

    val entityMap = mutableMapOf<String, Float>()
    // to build the TextLinks, the entity map must have at least one item
    entityMap[TextClassifier.TYPE_URL] = 0.01f
    for (entity in tcEntities ?: emptyList()) {
      entityMap[entity] = 0.9f
    }
    Mockito.doReturn(TextLinks.Builder("").addLink(0, 1, entityMap).build())
      .`when`(tc)
      .generateLinks(any(TextLinks.Request::class.java))
    return tc
  }
}