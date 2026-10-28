package com.github.kr328.clash.cloud

import com.github.kr328.clash.common.CloudFeatureAccess

object CloudTestAccount {
    val emails: List<String>
        get() = CloudFeatureAccess.testAccountEmails

    fun isTest(email: String?): Boolean = CloudFeatureAccess.isTestAccount(email)
}
