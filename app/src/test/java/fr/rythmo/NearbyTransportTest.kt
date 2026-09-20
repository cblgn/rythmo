package fr.rythmo

import android.os.Looper
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import fr.rythmo.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class NearbyTransportTest {
 private val scheduler=TestCoroutineScheduler()
 private val dispatcher=StandardTestDispatcher(scheduler)
 private val calls=mutableListOf<String>()
 private val sent=mutableListOf<Payload>()
 private var lifecycle:ConnectionLifecycleCallback?=null
 private var discovery:EndpointDiscoveryCallback?=null
 private var payloads:PayloadCallback?=null
 private var error:Exception?=null
 private lateinit var transport:NearbyTransport
 @Before fun setup() { Dispatchers.setMain(dispatcher); transport=create() }
 @After fun close() { transport.close();Dispatchers.resetMain() }
 private fun create(problem:()->String?={null}):NearbyTransport {
  val client=Proxy.newProxyInstance(ConnectionsClient::class.java.classLoader,arrayOf(ConnectionsClient::class.java)) { _,method,args ->
   calls.add(method.name)
   args?.forEach { when(it) { is ConnectionLifecycleCallback -> lifecycle=it; is EndpointDiscoveryCallback -> discovery=it; is PayloadCallback -> payloads=it; is Payload -> sent.add(it) } }
   if(Task::class.java.isAssignableFrom(method.returnType)) error?.let { Tasks.forException<Void>(it) } ?: Tasks.forResult<Void>(null) else null
  } as ConnectionsClient
  return NearbyTransport(RuntimeEnvironment.getApplication(),client,problem)
 }
 private fun pump() { repeat(3) { scheduler.runCurrent();shadowOf(Looper.getMainLooper()).idle() } }
 private fun waitFor(condition:()->Boolean) {
  val deadline=System.nanoTime()+5_000_000_000
  while(!condition() && System.nanoTime()<deadline) { pump();Thread.sleep(5) }
  assertTrue("Callback did not finish",condition())
 }
 private fun initiated(id:String="host") { lifecycle!!.onConnectionInitiated(id,ConnectionInfo("Teacher","1234",false));pump() }
 private fun connected(id:String="host") { initiated(id);transport.confirm(id,true);pump();lifecycle!!.onConnectionResult(id,ConnectionResolution(Status.RESULT_SUCCESS));pump() }
 @Test fun `discovery selects a single host and authenticated exchanges survive reconnect`() {
  transport.discover("Tablet");pump();assertEquals(ConnectionPhase.SEARCHING,transport.state.value.phase)
  discovery!!.onEndpointFound("host",DiscoveredEndpointInfo("service","Teacher"));pump()
  scheduler.advanceTimeBy(2501);pump();assertEquals(ConnectionPhase.CONNECTING,transport.state.value.phase)
  connected();assertEquals("host",transport.state.value.connected.single().id)
  val request=SyncMessage(type="sync_request")
  val result=CompletableFuture.supplyAsync { transport.exchange(request) }
  waitFor { sent.isNotEmpty() }
  assertEquals(request,SyncCodec.decode(sent.single().asBytes()!!))
  payloads!!.onPayloadReceived("host",Payload.fromBytes(SyncCodec.encode(request.copy(type="session_snapshot"))))
  waitFor { result.isDone };assertEquals("session_snapshot",result.get(1,TimeUnit.SECONDS).type)
  val old=transport.state.value.connected.single().connectionId
  lifecycle!!.onDisconnected("host");pump();assertEquals(ConnectionPhase.LOST,transport.state.value.phase)
  transport.connect(Endpoint("host","Teacher"),"Tablet");pump();connected()
  assertNotEquals(old,transport.state.value.connected.single().connectionId)
  transport.disconnect();pump();assertEquals(TransportState(),transport.state.value)
 }
 @Test fun `multiple discoveries remain selectable and stale discoveries cannot reconnect after close`() {
  transport.discover("Tablet");pump()
  for(id in listOf("a","b")) discovery!!.onEndpointFound(id,DiscoveredEndpointInfo("service",id))
  pump();scheduler.advanceTimeBy(2501);pump();assertFalse(calls.contains("requestConnection"))
  discovery!!.onEndpointLost("a");pump();assertEquals("b",transport.state.value.endpoints.single().id)
  transport.disconnect();pump();discovery!!.onEndpointFound("late",DiscoveredEndpointInfo("service","Late"));pump()
  assertTrue(transport.state.value.endpoints.isEmpty())
 }
 @Test fun `unsolicited and unconfirmed endpoints are rejected and failed association is visible`() {
  transport.connect(Endpoint("host","Teacher"),"Tablet");pump();initiated("intruder")
  assertTrue(calls.contains("rejectConnection"));assertTrue(transport.state.value.associations.isEmpty())
  lifecycle!!.onConnectionResult("host",ConnectionResolution(Status.RESULT_SUCCESS));pump()
  assertTrue(calls.contains("disconnectFromEndpoint"));assertTrue(transport.state.value.connected.isEmpty())
  initiated();transport.confirm("host",false);pump();assertTrue(transport.state.value.associations.isEmpty())
  lifecycle!!.onConnectionResult("host",ConnectionResolution(Status.RESULT_INTERNAL_ERROR));pump()
  assertEquals(ConnectionPhase.ERROR,transport.state.value.phase)
 }
 @Test fun `host responds only to associated endpoints and rename preserves connections`() {
  transport.advertise("Teacher") { _,request -> request.copy(type="response") };pump()
  assertTrue(transport.state.value.advertising)
  connected("pupil");val before=transport.state.value.connected
  transport.renameHost("New lesson");pump();assertEquals(before,transport.state.value.connected)
  assertEquals(2,calls.count { it=="startAdvertising" })
  val request=SyncMessage(type="sync_request")
  payloads!!.onPayloadReceived("unknown",Payload.fromBytes(SyncCodec.encode(request)));pump();assertTrue(sent.isEmpty())
  payloads!!.onPayloadReceived("pupil",Payload.fromBytes(SyncCodec.encode(request)));waitFor { sent.isNotEmpty() }
  assertEquals("response",SyncCodec.decode(sent.single().asBytes()!!).type)
  payloads!!.onPayloadReceived("pupil",Payload.fromBytes("invalid".toByteArray()));waitFor { transport.state.value.error != null }
 }
 @Test fun `availability errors discovery timeout and SDK failures are explicit`() {
  transport.close();transport=create { "Permission required" };transport.discover("Tablet");pump()
  assertEquals("Permission required",transport.state.value.error);assertFalse(calls.contains("startDiscovery"))
  transport.close();transport=create();transport.discover("Tablet");pump();scheduler.advanceTimeBy(16000);pump()
  assertEquals(ConnectionPhase.ERROR,transport.state.value.phase)
  error=IllegalStateException("Radio unavailable");transport.connect(Endpoint("host","Teacher"),"Tablet");pump()
  assertEquals("Radio unavailable",transport.state.value.error)
 }
 @Test fun `association timeout rejects the pending endpoint`() {
  transport.connect(Endpoint("host","Teacher"),"Tablet");pump();initiated()
  assertEquals(1,transport.state.value.associations.size)
  scheduler.advanceTimeBy(60001);pump();assertTrue(transport.state.value.associations.isEmpty());assertTrue(calls.contains("rejectConnection"))
 }
 private fun update(id: Long, status: Int, size: Long = 0) {
  payloads!!.onPayloadTransferUpdate("pupil", PayloadTransferUpdate.Builder()
   .setPayloadId(id).setStatus(status).setTotalBytes(size).setBytesTransferred(size).build())
  pump()
 }
 @Test fun `stream response waits for transfer success and large replies use private streams`() {
  transport.advertise("Teacher") { _,request -> request.copy(payload=kotlinx.serialization.json.JsonPrimitive("x".repeat(40000))) };pump();connected("pupil")
  val request=SyncMessage(type="sync_request")
  val bytes=SyncCodec.encode(request)
  val stream=Payload.fromStream(java.io.ByteArrayInputStream(bytes))
  payloads!!.onPayloadReceived("pupil",stream);pump()
  update(stream.id,PayloadTransferUpdate.Status.IN_PROGRESS,bytes.size.toLong())
  assertTrue(sent.isEmpty())
  update(stream.id,PayloadTransferUpdate.Status.SUCCESS,bytes.size.toLong())
  waitFor { sent.isNotEmpty() }
  val reply=sent.single();assertEquals(Payload.Type.STREAM,reply.type)
  assertEquals(request.requestId,SyncCodec.decode(reply.asStream()!!.asInputStream()!!.readBytes()).requestId)
  update(reply.id,PayloadTransferUpdate.Status.SUCCESS)
  lifecycle!!.onDisconnected("pupil");pump();assertEquals(ConnectionPhase.IDLE,transport.state.value.phase)
 }
 @Test fun `failed oversized and abandoned streams never reach the session handler`() {
  var handled=0
  transport.advertise("Teacher") { _,request -> handled++;request };pump();connected("pupil")
  val bytes=SyncCodec.encode(SyncMessage(type="sync_request"))
  for(status in listOf(PayloadTransferUpdate.Status.FAILURE,PayloadTransferUpdate.Status.CANCELED)) {
   val stream=Payload.fromStream(java.io.ByteArrayInputStream(bytes))
   payloads!!.onPayloadReceived("pupil",stream);pump();update(stream.id,status)
  }
  val oversized=Payload.fromStream(java.io.ByteArrayInputStream(bytes))
  payloads!!.onPayloadReceived("pupil",oversized);pump()
  update(oversized.id,PayloadTransferUpdate.Status.IN_PROGRESS,SyncCodec.MAX_BYTES.toLong()+1)
  val unfinished=Payload.fromStream(java.io.ByteArrayInputStream(bytes))
  payloads!!.onPayloadReceived("pupil",unfinished);pump();scheduler.advanceTimeBy(45001);pump()
  assertTrue(calls.contains("cancelPayload"));assertEquals(0,handled)
  val disconnecting=Payload.fromStream(java.io.ByteArrayInputStream(bytes))
  payloads!!.onPayloadReceived("pupil",disconnecting);pump();lifecycle!!.onDisconnected("pupil");pump()
  update(disconnecting.id,PayloadTransferUpdate.Status.SUCCESS);assertEquals(0,handled)
 }
 @Test fun `disconnect and send failures complete pending exchanges without losing the local request`() {
  val request=SyncMessage(type="submit_result",payload=kotlinx.serialization.json.JsonPrimitive("x".repeat(40000)))
  transport.connect(Endpoint("host","Teacher"),"Tablet");pump();connected()
  val result=CompletableFuture.supplyAsync { runCatching { transport.exchange(request) } }
  waitFor { sent.isNotEmpty() };transport.disconnect();pump();waitFor { result.isDone }
  assertTrue(result.get().exceptionOrNull()!!.message!!.contains("fermée"))
  transport.connect(Endpoint("host","Teacher"),"Tablet");pump();connected()
  error=IllegalStateException("Send failed")
  val failed=CompletableFuture.supplyAsync { runCatching { transport.exchange(request) } }
  waitFor { failed.isDone };assertEquals("Send failed",failed.get().exceptionOrNull()!!.message)
  error=null;transport.disconnect();pump()
  val offline=CompletableFuture.supplyAsync { runCatching { transport.exchange(request) } }
  waitFor { offline.isDone };assertTrue(offline.get().isFailure)
 }
 @Test fun `late discovery joins once and explicit confirmation ignores unknown endpoints`() {
  transport.discover("Tablet");pump();scheduler.advanceTimeBy(3000);pump()
  discovery!!.onEndpointFound("host",DiscoveredEndpointInfo("service","Teacher"));pump();scheduler.advanceTimeBy(501);pump()
  assertEquals(ConnectionPhase.CONNECTING,transport.state.value.phase)
  transport.confirm("unknown",true);pump();assertFalse(calls.contains("acceptConnection"))
  connected();val requests=calls.count { it=="requestConnection" }
  transport.connect(Endpoint("other","Other"),"Tablet");pump();assertEquals(requests,calls.count { it=="requestConnection" })
 }

}
