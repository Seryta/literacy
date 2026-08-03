package com.literacy.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.literacy.agent.data.HanziDataSource
import com.literacy.app.settings.AppSettings

/** LearnViewModel 工厂（从设置读 provider 配置 + 字库 + Room 存储注入）。 */
class LearnViewModelFactory(
    private val settings: AppSettings,
    private val hanzi: HanziDataSource,
    private val store: com.literacy.agent.store.LearningStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LearnViewModel(settings, hanzi, store) as T
}
