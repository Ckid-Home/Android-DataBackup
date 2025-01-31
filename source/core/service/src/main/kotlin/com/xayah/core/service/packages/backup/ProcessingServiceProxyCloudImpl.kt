package com.xayah.core.service.packages.backup

import android.content.Context
import android.content.Intent
import com.xayah.core.service.AbstractProcessingServiceProxy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ProcessingServiceProxyCloudImpl @Inject constructor() : AbstractProcessingServiceProxy() {
    @Inject
    @ApplicationContext
    override lateinit var context: Context

    override val intent by lazy { Intent(context, BackupServiceCloudImpl::class.java) }
}
