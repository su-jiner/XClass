package cn.xiao.xyaax.benchmark

import androidx.benchmark.macro.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = "cn.xiao.xyaax",
            profileBlock = {
                startActivityAndWait()
                device.waitForIdle()
            }
        )
    }
}
