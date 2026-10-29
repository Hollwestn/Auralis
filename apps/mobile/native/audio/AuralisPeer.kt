package expo.modules.audio
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.*

/** Connection-scoped TLS pin. System trust settings and other requests are never changed. */
object AuralisPeer {
 private const val MAX=3_000_000_000L
 private fun hex(bytes: ByteArray)=bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
 private fun owned(context:Context,uri:String):File {
  val parsed=Uri.parse(uri);require(parsed.scheme=="file") { "Expected an app-owned file" }
  val file=File(parsed.path ?: error("Missing file path")).canonicalFile
  require(listOf(context.filesDir,context.cacheDir).any { file.path.startsWith(it.canonicalPath+File.separator) }) { "File must belong to Auralis" };return file
 }
 fun request(context:Context,descriptor:String,method:String,path:String,nonce:String,payload:String,sourceUri:String,destinationUri:String):Map<String,Any> {
  val info=JSONObject(descriptor);val host=info.getString("host");val parts=host.split('.').map { it.toIntOrNull() ?: -1 }
  require(parts.size==4 && parts.all{it in 0..255} && parts.joinToString(".")==host && (parts[0]==10 || parts[0]==192&&parts[1]==168 || parts[0]==172&&parts[1] in 16..31)) { "Only local Wi-Fi IPv4 addresses are supported" }
  require(info.getInt("v")==1 && info.getInt("port") in 1..65535 && nonce.matches(Regex("[A-Za-z0-9-]{16,100}"))) { "Invalid pairing" }
  val format=java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",java.util.Locale.US);format.timeZone=java.util.TimeZone.getTimeZone("UTC");format.isLenient=false
  val expiry=info.getString("expires").replace(Regex("\\.\\d{1,9}Z$"),"Z");val until=format.parse(expiry)?.time ?: 0L;val now=System.currentTimeMillis();require(until>now && until<now+1_000_000L) { "Pairing session expired" }
  val expected=info.getString("certificate").lowercase();val secret=info.getString("secret");require(expected.matches(Regex("[a-f0-9]{64}")) && secret.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid certificate pin" }
  require(method in listOf("GET","POST") && (path in listOf("/pair","/bundle","/upload") || path.matches(Regex("/pair/status/[a-f0-9-]{36}")))) { "Unsupported transfer endpoint" }
  val trust=object:X509TrustManager { override fun getAcceptedIssuers()=emptyArray<X509Certificate>();override fun checkClientTrusted(chain:Array<X509Certificate>,auth:String){throw java.security.cert.CertificateException("Client trust is unsupported")};override fun checkServerTrusted(chain:Array<X509Certificate>,auth:String){if(chain.isEmpty())throw java.security.cert.CertificateException("Missing certificate");chain[0].checkValidity();val actual=hex(MessageDigest.getInstance("SHA-256").digest(chain[0].encoded));if(!MessageDigest.isEqual(expected.toByteArray(),actual.toByteArray()))throw java.security.cert.CertificateException("Certificate does not match the scanned QR")}}
  val tls=SSLContext.getInstance("TLS");tls.init(null,arrayOf(trust),null)
  val connection=URL("https://$host:${info.getInt("port")}$path").openConnection() as HttpsURLConnection
  connection.sslSocketFactory=tls.socketFactory;connection.hostnameVerifier=HostnameVerifier { name,_->name==host };connection.instanceFollowRedirects=false;connection.requestMethod=method;connection.connectTimeout=15000;connection.readTimeout=30000;connection.setRequestProperty("Authorization","Auralis $secret");connection.setRequestProperty("X-Auralis-Device",nonce)
  var partial:File?=null
  try {
   if(method=="POST") {
    connection.doOutput=true
    if(path=="/upload") { val source=owned(context,sourceUri);require(source.isFile&&source.length()<=MAX){"Selected archive is missing or larger than 3 GB"};connection.setRequestProperty("Content-Type","application/zip");connection.setFixedLengthStreamingMode(source.length());source.inputStream().use { input->connection.outputStream.use { output->input.copyTo(output,131072) } } }
    else { val body=payload.toByteArray(Charsets.UTF_8);require(body.size<=4096);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(body.size);connection.outputStream.use { it.write(body) } }
   }
   val status=connection.responseCode
   if(status==200 && path=="/bundle") {
    val target=owned(context,destinationUri);target.parentFile?.mkdirs();val declared=connection.contentLengthLong;require(declared in 1..MAX){"Archive exceeds the phone size limit"};val digest=MessageDigest.getInstance("SHA-256");val temp=File.createTempFile("auralis-peer-",".part",target.parentFile);partial=temp
    var size=0L;connection.inputStream.use { input->temp.outputStream().use { output->val buffer=ByteArray(131072);while(true){val n=input.read(buffer);if(n<0)break;size+=n;require(size<=declared&&size<=MAX){"Unexpected archive size"};digest.update(buffer,0,n);output.write(buffer,0,n)} } }
    require(size==declared && hex(digest.digest())==connection.getHeaderField("X-Auralis-SHA256")){"Archive integrity check failed"}
    require(!target.exists() && temp.renameTo(target)){"Could not save the archive"};partial=null
    return mapOf("status" to status,"body" to "{}","uri" to destinationUri)
   }
   val input=if(status>=400)connection.errorStream else connection.inputStream
   val bytes=input?.use { stream->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(1024);while(out.size()<=65536){val n=stream.read(buffer);if(n<0)break;out.write(buffer,0,n)};out.toByteArray() } ?: ByteArray(0);require(bytes.size<=65536){"Unexpected response size"};return mapOf("status" to status,"body" to bytes.toString(Charsets.UTF_8))
  } finally {partial?.delete();connection.disconnect()}
 }
}
