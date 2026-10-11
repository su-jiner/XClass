package cn.xiao.xyaax.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupCold() {
        rule.measureRepeated(
            packageName = "cn.xiao.xyaax",
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                pressHome()
            }
        ) {
            startActivityAndWait()
        }
    }

    @Test
    fun startupWarm() {
        rule.measureRepeated(
            packageName = "cn.xiao.xyaax",
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Full(),
            startupMode = StartupMode.WARM,
            iterations = 5
        ) {
            startActivityAndWait()
        }
    }
}
