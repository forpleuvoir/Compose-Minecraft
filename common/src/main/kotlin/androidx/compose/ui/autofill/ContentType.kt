/*
 * Copyright 2024 The Android Open Source Project
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

package androidx.compose.ui.autofill

import kotlin.jvm.JvmInline

// TODO https://youtrack.jetbrains.com/issue/CMP-7154/Adopt-Autofill-semantic-properties

sealed interface ContentType {
    companion object {
        val Username: ContentType = PlatformContentType(1L shl 0)
        val Password: ContentType = PlatformContentType(1L shl 1)
        val EmailAddress: ContentType = PlatformContentType(1L shl 2)
        val NewUsername: ContentType = PlatformContentType(1L shl 3)
        val NewPassword: ContentType = PlatformContentType(1L shl 4)
        val PostalAddress: ContentType = PlatformContentType(1L shl 5)
        val PostalCode: ContentType = PlatformContentType(1L shl 6)
        val CreditCardNumber: ContentType = PlatformContentType(1L shl 7)
        val CreditCardSecurityCode: ContentType = PlatformContentType(1L shl 8)
        val CreditCardExpirationDate: ContentType = PlatformContentType(1L shl 9)
        val CreditCardExpirationMonth: ContentType = PlatformContentType(1L shl 10)
        val CreditCardExpirationYear: ContentType = PlatformContentType(1L shl 11)
        val CreditCardExpirationDay: ContentType = PlatformContentType(1L shl 12)
        val AddressCountry: ContentType = PlatformContentType(1L shl 13)
        val AddressRegion: ContentType = PlatformContentType(1L shl 14)
        val AddressLocality: ContentType = PlatformContentType(1L shl 15)
        val AddressStreet: ContentType = PlatformContentType(1L shl 16)
        val AddressAuxiliaryDetails: ContentType = PlatformContentType(1L shl 17)
        val PostalCodeExtended: ContentType = PlatformContentType(1L shl 18)
        val PersonFullName: ContentType = PlatformContentType(1L shl 19)
        val PersonFirstName: ContentType = PlatformContentType(1L shl 20)
        val PersonLastName: ContentType = PlatformContentType(1L shl 21)
        val PersonMiddleName: ContentType = PlatformContentType(1L shl 22)
        val PersonMiddleInitial: ContentType = PlatformContentType(1L shl 23)
        val PersonNamePrefix: ContentType = PlatformContentType(1L shl 24)
        val PersonNameSuffix: ContentType = PlatformContentType(1L shl 25)
        val PhoneNumber: ContentType = PlatformContentType(1L shl 26)
        val PhoneNumberDevice: ContentType = PlatformContentType(1L shl 27)
        val PhoneCountryCode: ContentType = PlatformContentType(1L shl 28)
        val PhoneNumberNational: ContentType = PlatformContentType(1L shl 29)
        val Gender: ContentType = PlatformContentType(1L shl 30)
        val BirthDateFull: ContentType = PlatformContentType(1L shl 31)
        val BirthDateDay: ContentType = PlatformContentType(1L shl 32)
        val BirthDateMonth: ContentType = PlatformContentType(1L shl 33)
        val BirthDateYear: ContentType = PlatformContentType(1L shl 34)
        val SmsOtpCode: ContentType = PlatformContentType(1L shl 35)
    }

    operator fun plus(other: ContentType): ContentType
}

@JvmInline
private value class PlatformContentType(val type: Long) : ContentType {
    override fun plus(other: ContentType): ContentType {
        other as PlatformContentType
        return PlatformContentType(type or other.type)
    }
}
