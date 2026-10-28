package com.example.xdnetworksdk

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.xdnetworksdk.ui.theme.XDNetWorkSDKTheme
import com.xd.network.LoggingInterceptor
import com.xd.network.NetworkCall
import com.xd.network.NetworkLogger
import com.xd.network.XDNetworkClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XDNetWorkSDKTheme { NetworkSdkDemo() } }
    }
}

@Composable
private fun NetworkSdkDemo() {
    val client = remember {
        XDNetworkClient.Builder()
            .baseUrl("https://httpbin.org/")
            .defaultHeader("Accept", "application/json")
            .addInterceptor(LoggingInterceptor(NetworkLogger { Log.d("XDNetwork", it) }))
            .build()
    }
    var activeCall by remember { mutableStateOf<NetworkCall?>(null) }
    var result by remember { mutableStateOf("点击按钮发起 GET 请求") }
    var loading by remember { mutableStateOf(false) }

    DisposableEffect(client) {
        onDispose {
            activeCall?.cancel()
            client.close()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { contentPadding ->
        Column(
            modifier = Modifier.padding(contentPadding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("XdNet", style = MaterialTheme.typography.headlineMedium)
            Text("独立 Android Library · 无第三方网络依赖")
            Button(
                enabled = !loading,
                onClick = {
                    loading = true
                    result = "请求中…"
                    activeCall = client.get("get", mapOf("source" to "android-demo")).also { call ->
                        call.enqueue { responseResult ->
                            loading = false
                            activeCall = null
                            result = responseResult.fold(
                                onSuccess = { "HTTP ${it.code}\n\n${it.bodyString()}" },
                                onFailure = { "请求失败：${it.message}" }
                            )
                        }
                    }
                }
            ) {
                if (loading) CircularProgressIndicator()
                else Text("发送测试请求")
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(result, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
