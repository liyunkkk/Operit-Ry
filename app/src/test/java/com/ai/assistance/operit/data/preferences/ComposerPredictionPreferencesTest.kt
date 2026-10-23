package com.ai.assistance.operit.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * 组合输入预测开关的默认值与重置语义。
 *
 * 隔离说明：模块级 `Context.displayPreferencesDataStore` 委托在一个 JVM 内只创建一个
 * DataStore，绑定首个访问它的 Context，所以每个用例都要清空该单例并绑定到自己的临时目录。
 * DataStore 1.0.x 用 `File.renameTo` 把临时文件改写成数据文件；Windows 上该调用在目标文件
 * 已存在时必定失败（Linux/Android 的 `rename(2)` 会直接覆盖），因此一个数据文件在本机只允许
 * 一次真实写入。需要两次写入的重置用例在 Windows 上跳过，见 `ApiPreferencesResetFailureTest`
 * 对同一限制的说明。
 */
class ComposerPredictionPreferencesTest {
    @Test
    fun `defaults on and a saved off reaches a new manager`() = runBlocking {
        val context = isolatedContext()
        val constructor = managerConstructor()
        val preferences = constructor.newInstance(context)

        assertTrue(preferences.composerPredictionsEnabled.first())
        preferences.saveDisplaySettings(composerPredictionsEnabled = false)
        assertFalse(preferences.composerPredictionsEnabled.first())
        assertFalse(constructor.newInstance(context).composerPredictionsEnabled.first())
    }

    @Test
    fun `another setting keeps the saved off and reset restores the default on`() = runBlocking {
        assumeFalse(
            "DataStore 1.0.x cannot rewrite an existing preferences file on Windows",
            System.getProperty("os.name").orEmpty().startsWith("Windows"),
        )

        val context = isolatedContext()
        val preferences = managerConstructor().newInstance(context)

        preferences.saveDisplaySettings(composerPredictionsEnabled = false)
        preferences.saveDisplaySettings(showFpsCounter = true)
        assertFalse(preferences.composerPredictionsEnabled.first())

        preferences.resetDisplaySettings()
        assertTrue(preferences.composerPredictionsEnabled.first())
    }

    /** 清空委托缓存，让本次用例绑定到自己的临时目录。 */
    private fun isolatedContext(): Context {
        val facade = Class.forName("com.ai.assistance.operit.data.preferences.DisplayPreferencesManagerKt")
        val delegateField = facade.getDeclaredField("displayPreferencesDataStore\$delegate")
            .apply { isAccessible = true }
        val delegate = delegateField.get(null)
        delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
            .set(delegate, null)

        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(kotlin.io.path.createTempDirectory("composer-preferences").toFile())
        return context
    }

    private fun managerConstructor() =
        DisplayPreferencesManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
}
