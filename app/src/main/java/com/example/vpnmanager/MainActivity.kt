package com.example.vpnmanager

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

private data class VpnConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val country: String = "Unknown",
    val source: String = "Manual",
    val content: String,
    val valid: Boolean = true,
    val working: Boolean = false,
    val latencyMs: Long = -1,
    val score: Int = 0,
    val favorite: Boolean = false,
    val lastTest: Long = 0L,
    val error: String = ""
)

private object Store {
    private const val PREF = "vpn_manager"
    private const val KEY = "configs"
    private const val SOURCES = "sources"
    fun load(ctx: Context): MutableList<VpnConfig> {
        val raw = ctx.getSharedPreferences(PREF, 0).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
    }
    fun save(ctx: Context, list: List<VpnConfig>) {
        val arr = JSONArray(); list.forEach { arr.put(toJson(it)) }
        ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, arr.toString()).apply()
    }
    fun sources(ctx: Context): MutableList<String> {
        val arr = JSONArray(ctx.getSharedPreferences(PREF,0).getString(SOURCES,"[]") ?: "[]")
        return MutableList(arr.length()) { arr.getString(it) }
    }
    fun saveSources(ctx: Context, list: List<String>) { val a=JSONArray(); list.forEach(a::put); ctx.getSharedPreferences(PREF,0).edit().putString(SOURCES,a.toString()).apply() }
    private fun toJson(v: VpnConfig)=JSONObject().apply { put("id",v.id);put("name",v.name);put("protocol",v.protocol);put("country",v.country);put("source",v.source);put("content",v.content);put("valid",v.valid);put("working",v.working);put("latency",v.latencyMs);put("score",v.score);put("favorite",v.favorite);put("lastTest",v.lastTest);put("error",v.error) }
    private fun fromJson(o: JSONObject)=VpnConfig(o.optString("id",UUID.randomUUID().toString()),o.optString("name","VPN"),o.optString("protocol","Unknown"),o.optString("country","Unknown"),o.optString("source","Manual"),o.optString("content"),o.optBoolean("valid",true),o.optBoolean("working",false),o.optLong("latency",-1),o.optInt("score",0),o.optBoolean("favorite",false),o.optLong("lastTest",0),o.optString("error"))
}

private object Parser {
    fun parse(name: String, content: String, source: String): VpnConfig? {
        val clean = content.trim()
        if (clean.isBlank()) return null
        val protocol = when {
            Regex("(?im)^\\s*\\[Interface\\]").containsMatchIn(clean) && Regex("(?im)^\\s*PrivateKey\\s*=").containsMatchIn(clean) -> "WireGuard"
            Regex("(?im)^\\s*client\\b").containsMatchIn(clean) || Regex("(?im)^\\s*dev\\s+(tun|tap)").containsMatchIn(clean) || Regex("(?im)^\\s*remote\\s+").containsMatchIn(clean) -> "OpenVPN"
            else -> "Unknown"
        }
        if (protocol == "Unknown") return null
        val country = guessCountry(name + " " + source + " " + clean)
        val valid = if (protocol == "WireGuard") Regex("(?im)^\\s*PrivateKey\\s*=").containsMatchIn(clean) && Regex("(?im)^\\s*Endpoint\\s*=").containsMatchIn(clean) else Regex("(?im)^\\s*remote\\s+").containsMatchIn(clean)
        return VpnConfig(UUID.randomUUID().toString(), name.take(40), protocol, country, source, clean, valid)
    }
    private fun guessCountry(s: String): String {
        val x=s.lowercase(Locale.US)
        val map=linkedMapOf("germany" to "Germany","de" to "Germany","france" to "France","fr" to "France","netherlands" to "Netherlands","nl" to "Netherlands","united kingdom" to "United Kingdom","uk" to "United Kingdom","gb" to "United Kingdom","usa" to "United States","us" to "United States","canada" to "Canada","ca" to "Canada","singapore" to "Singapore","sg" to "Singapore","japan" to "Japan","jp" to "Japan","finland" to "Finland","fi" to "Finland","sweden" to "Sweden","se" to "Sweden","poland" to "Poland","pl" to "Poland","turkey" to "Turkey","tr" to "Turkey")
        return map.entries.firstOrNull { Regex("(^|[^a-z])${Regex.escape(it.key)}([^a-z]|$)").containsMatchIn(x) }?.value ?: "Unknown"
    }
}

private class GitHubFetcher {
    fun fetch(url: String): List<Pair<String,String>> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout=15000; conn.readTimeout=20000; conn.setRequestProperty("User-Agent","VPN-Manager/1.0")
        val body=conn.inputStream.bufferedReader().use(BufferedReader::readText)
        conn.disconnect()
        val out=mutableListOf<Pair<String,String>>()
        if (url.contains("api.github.com")) {
            val arr=JSONArray(body)
            for(i in 0 until arr.length()) {
                val o=arr.getJSONObject(i); if(o.optString("type")=="file") { val dl=o.optString("download_url"); if(dl.isNotBlank()) out += o.optString("name") to download(dl) }
            }
        } else out += (url.substringAfterLast('/').ifBlank { "github-source" }) to body
        return out
    }
    private fun download(url:String):String { val c=URL(url).openConnection() as HttpURLConnection; c.connectTimeout=15000;c.readTimeout=20000;c.setRequestProperty("User-Agent","VPN-Manager/1.0"); return c.inputStream.bufferedReader().use { it.readText() }.also{c.disconnect()} }
}

private class WireGuardController(private val ctx: Context) {
    private val backend by lazy { GoBackend(ctx.applicationContext) }
    private var active: Tunnel? = null
    fun connect(v: VpnConfig): Result<Unit> = runCatching {
        require(v.protocol=="WireGuard") { "Only WireGuard is a real tunnel in this build." }
        val prepared=VpnService.prepare(ctx)
        if(prepared!=null) throw VpnPermissionRequired()
        active?.let { backend.setState(it, Tunnel.State.DOWN, null) }
        val config=Config.parse(v.content.byteInputStream())
        val tunnel=object:Tunnel { override fun getName()="vpn"+v.id.replace("-","").take(10); override fun onStateChange(newState:Tunnel.State){} }
        backend.setState(tunnel, Tunnel.State.UP, config)
        active=tunnel
    }
    fun disconnect() { active?.let { runCatching{backend.setState(it,Tunnel.State.DOWN,null)} }; active=null }
}
class VpnPermissionRequired: Exception()

class MainActivity : ComponentActivity() {
    private lateinit var controller: WireGuardController
    private var pendingConnect: VpnConfig?=null
    private val vpnPermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){ r -> if(r.resultCode==Activity.RESULT_OK) pendingConnect?.let{ connect(it) } else toast("اجازه VPN داده نشد") }
    private val importLauncher=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){ uris -> uris?.forEach{ importUri(it) } }
    private val exportLauncher=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){ uri -> uri?.let{exportZip(it)} }

    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);controller=WireGuardController(this);setContent{App()}}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun connect(v:VpnConfig){thread{val r=controller.connect(v);runOnUiThread{if(r.isSuccess)toast("اتصال برقرار شد: ${v.name}") else {val e=r.exceptionOrNull();if(e is VpnPermissionRequired){pendingConnect=v;VpnService.prepare(this)?.let { vpnPermission.launch(it) }}else toast("اتصال ناموفق: ${e?.message}")}}}}
    private fun importUri(uri:Uri){thread{try{val name=uri.lastPathSegment?.substringAfterLast('/')?:"imported.conf";val text=contentResolver.openInputStream(uri)!!.bufferedReader().use{it.readText()};val p=Parser.parse(name,text,"Imported");if(p!=null){val l=Store.load(this);l += p;Store.save(this,l);runOnUiThread{toast("وارد شد: $name")}}else runOnUiThread{toast("فرمت پشتیبانی‌شده پیدا نشد")}}catch(e:Exception){runOnUiThread{toast("Import failed: ${e.message}")}}}}
    private fun exportZip(uri:Uri){thread{try{val list=Store.load(this);contentResolver.openOutputStream(uri)!!.use{os->ZipOutputStream(os).use{z->{list.forEachIndexed{i,v->val ext=if(v.protocol=="WireGuard")"conf" else "ovpn";z.putNextEntry(ZipEntry("configs/${i+1}_${safe(v.name)}.$ext"));z.write(v.content.toByteArray());z.closeEntry()};z.putNextEntry(ZipEntry("vpn-list.json"));z.write(JSONArray(list.map{JSONObject().put("name",it.name).put("protocol",it.protocol).put("country",it.country).put("source",it.source)}).toString(2).toByteArray());z.closeEntry()}}};runOnUiThread{toast("Export انجام شد")}}catch(e:Exception){runOnUiThread{toast("Export failed: ${e.message}")}}}}
    private fun safe(s:String)=s.replace(Regex("[^A-Za-z0-9._-]"),"_")

    @Composable fun App(){
        var list by remember{mutableStateOf(Store.load(this))};var selected by remember{mutableStateOf<VpnConfig?>(null)};var sort by remember{mutableStateOf("score")};var pageSize by remember{mutableIntStateOf(50)};var page by remember{mutableIntStateOf(0)};var sources by remember{mutableStateOf(Store.sources(this))};var sourceText by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)}
        fun save(new:List<VpnConfig>){list=new;Store.save(this,new)}
        val sorted=remember(list,sort){when(sort){"latency"->list.sortedWith(compareBy{if(it.latencyMs<0)Long.MAX_VALUE else it.latencyMs});"country"->list.sortedBy{it.country};"favorite"->list.sortedWith(compareByDescending<VpnConfig>{it.favorite}.thenByDescending{it.score});"name"->list.sortedBy{it.name.lowercase()};else->list.sortedByDescending{it.score}}}
        val shown=sorted.drop(page*pageSize).take(pageSize)
        MaterialTheme{Scaffold(topBar={TopAppBar(title={Text("VPN Manager",fontWeight=FontWeight.Bold)},actions={IconButton(onClick={busy=true;thread{val all=mutableListOf<VpnConfig>();sources.forEach{s->runCatching{GitHubFetcher().fetch(s).forEach{(n,c)->Parser.parse(n,c,s)?.let{all += it}}}};val merged=(list+all).distinctBy{it.protocol+"|"+it.content};save(merged);runOnUiThread{busy=false;toast("GitHub بروزرسانی شد: ${all.size} کانفیگ")}}}){Icon(Icons.Default.Refresh,"Refresh")};IconButton(onClick={exportLauncher.launch("VPN_Manager_Export.zip")}){Icon(Icons.Default.Share,"Export")}})},floatingActionButton={FloatingActionButton(onClick={importLauncher.launch(arrayOf("text/*","application/octet-stream","application/x-openvpn-profile"))}){Icon(Icons.Default.Add,"Import")}}){pad->Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)){if(busy)LinearProgressIndicator(Modifier.fillMaxWidth());Dashboard(list);Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){Button(onClick={if(list.isNotEmpty()){val targets=list.take(pageSize);busy=true;thread{val res=targets.map{testConfig(it)};save(list.map{old->res.find{it.id==old.id}?:old});runOnUiThread{busy=false;toast("تست $pageSize کانفیگ تمام شد")}}}}){Text("تست $pageSize")};Button(onClick={save(list.filter{it.valid})}){Text("حذف نامعتبرها")};Button(onClick={save(emptyList())},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("حذف همه")}};Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterButton("امتیاز",sort=="score"){sort="score"};FilterButton("Ping",sort=="latency"){sort="latency"};FilterButton("کشور",sort=="country"){sort="country"};FilterButton("⭐",sort=="favorite"){sort="favorite"};Text("${list.size} VPN",Modifier.padding(8.dp))};Spacer(Modifier.height(6.dp));LazyColumn(Modifier.weight(1f)){items(shown,key={it.id}){v->VpnCard(v,selected?.id==v.id,{selected=v},{save(list.map{if(it.id==v.id)it.copy(favorite=!it.favorite)else it})},{connect(v)},{save(list.filterNot{it.id==v.id})})}};Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){Text("صفحه ${page+1}/${maxOf(1,(sorted.size+pageSize-1)/pageSize)}");Row{TextButton(onClick={page=(page-1).coerceAtLeast(0)}){Text("قبلی")};TextButton(onClick={page=(page+1).coerceAtMost(maxOf(0,(sorted.size-1)/pageSize))}){Text("بعدی")}}};Spacer(Modifier.height(4.dp));Text("GitHub Sources",fontWeight=FontWeight.Bold);Row(Modifier.fillMaxWidth()){OutlinedTextField(sourceText,{sourceText=it},Modifier.weight(1f),singleLine=true,label={Text("URL")});Button(onClick={if(sourceText.isNotBlank()){sources=(sources+sourceText).distinct();Store.saveSources(this,sources);sourceText=""}}){Text("افزودن")}};sources.take(3).forEach{Text("• $it",style=MaterialTheme.typography.bodySmall)}}}}
    }
    private fun testConfig(v:VpnConfig):VpnConfig { if(!v.valid||v.protocol!="WireGuard") return v.copy(working=false,score=0,lastTest=System.currentTimeMillis(),error="Unsupported/invalid");val start=System.currentTimeMillis();return try{val r=controller.connect(v);if(r.isFailure) throw r.exceptionOrNull()!!;val latency=System.currentTimeMillis()-start;val ok=runCatching{val c=URL("https://www.google.com/generate_204").openConnection() as HttpURLConnection;c.connectTimeout=7000;c.readTimeout=7000;c.requestMethod="GET";val code=c.responseCode;c.disconnect();code in 200..399}.getOrDefault(false);controller.disconnect();val score=((if(ok)55 else 0)+(40-(latency.coerceAtMost(4000)/100)*10).coerceAtLeast(0)).coerceIn(0,100).toInt();v.copy(working=ok,latencyMs=latency,score=score,lastTest=System.currentTimeMillis(),error=if(ok)"" else "Internet test failed")}catch(e:Exception){controller.disconnect();v.copy(working=false,latencyMs=-1,score=0,lastTest=System.currentTimeMillis(),error=e.message?:("test failed"))}}

    @Composable fun Dashboard(list:List<VpnConfig>){Card(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.SpaceAround){Stat("کل",list.size.toString());Stat("سالم",list.count{it.working}.toString());Stat("نامعتبر",list.count{!it.valid}.toString());Stat("⭐",list.count{it.favorite}.toString())}}}
    @Composable fun Stat(a:String,b:String){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(b,fontWeight=FontWeight.Bold);Text(a,style=MaterialTheme.typography.labelSmall)}}
    @Composable fun FilterButton(t:String,on:Boolean,click:()->Unit){FilterChip(selected=on,onClick=click,label={Text(t)})}
    @Composable fun VpnCard(v:VpnConfig,selected:Boolean,select:()->Unit,fav:()->Unit,connect:()->Unit,delete:()->Unit){Card(Modifier.fillMaxWidth().padding(vertical=4.dp).clickable{select()},colors=CardDefaults.cardColors(containerColor=if(selected)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)){Column(Modifier.padding(12.dp)){Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){Text(v.countryFlag()+" "+v.name,Modifier.weight(1f),fontWeight=FontWeight.Bold);IconButton(onClick=fav){Icon(if(v.favorite)Icons.Default.Star else Icons.Default.StarBorder,"Favorite")};IconButton(onClick=delete){Icon(Icons.Default.Delete,"Delete")}};Text("${v.protocol} • ${v.country} • ${if(v.latencyMs>0)v.latencyMs.toString()+" ms" else "Ping -"}");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(if(v.working)"● Working" else if(!v.valid)"● Invalid" else "● Untested",color=if(v.working)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant);Text("Score ${v.score}/100");Button(onClick=connect,enabled=v.valid){Text("اتصال")}};if(v.error.isNotBlank())Text(v.error,style=MaterialTheme.typography.labelSmall)}}}
    private fun VpnConfig.countryFlag():String=when(country){"Germany"->"🇩🇪";"France"->"🇫🇷";"Netherlands"->"🇳🇱";"United Kingdom"->"🇬🇧";"United States"->"🇺🇸";"Canada"->"🇨🇦";"Japan"->"🇯🇵";"Singapore"->"🇸🇬";else->"🌐"}
}
