package com.hmgcarapp.crossdrop

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hmgcarapp.core.CarSignals
import com.hmgcarapp.core.log.HmgLog
import com.hmgcarapp.crossdrop.app.DropController
import com.hmgcarapp.crossdrop.ui.CrossDropApp
import com.hmgcarapp.pleos.designsystem.restriction.PleosUxRestrictionsProvider
import com.hmgcarapp.pleos.designsystem.theme.PleosTheme

class MainActivity : ComponentActivity() {
    private var controller: DropController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HmgLog.i("crossdrop", "onCreate")
        // 런타임 권한 중 이 앱이 선언한 것만 요청한다(차속 = 정차 판정). 권한 대화상자는 정차 중에만 처리할 수 있다.
        val declared = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val toRequest = listOf("android.car.permission.CAR_SPEED").filter { it in declared && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), 1)
        val c = DropController(this, CarSignals.create(this))
        controller = c
        setContent { PleosTheme { PleosUxRestrictionsProvider { CrossDropApp(c) } } }
    }

    override fun onStop() { controller?.saveNow(); super.onStop() }

    override fun onDestroy() {
        controller?.release()
        controller = null
        super.onDestroy()
    }
}
