package fi.vtt.nubotest;

import android.app.ListActivity;
import android.content.SharedPreferences;
import android.graphics.ImageFormat;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RendererCommon;
import org.webrtc.SessionDescription;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRendererGui;

import java.util.Map;

import fi.vtt.nubomedia.kurentoroomclientandroid.RoomError;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomListener;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomNotification;
import fi.vtt.nubomedia.kurentoroomclientandroid.RoomResponse;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMMediaConfiguration;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMPeerConnection;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMWebRTCPeer;

import fi.vtt.nubotest.util.Constants;

/**
 * Activity for receiving the video stream of a peer
 * (based on PeerVideoActivity of Pubnub's video chat tutorial example.
 *
 */
public class PeerVideoActivity extends ListActivity implements NBMWebRTCPeer.Observer, RoomListener {
    private String TAG = "PeerVideoActivity";

    private NBMMediaConfiguration peerConnectionParameters;
    private NBMWebRTCPeer nbmWebRTCPeer;

    private SessionDescription localSdp;
    private SessionDescription remoteSdp;

    private VideoRenderer.Callbacks localRender;
    private VideoRenderer.Callbacks remoteRender;
    private GLSurfaceView videoView;

    private SharedPreferences mSharedPreferences;

    private int publishVideoRequestId;
    private int sendIceCandidateRequestId;

    private TextView mCallStatus;

    private String username, calluser;
    private boolean backPressed = false;
    private Thread  backPressedThread = null;


    private static final int LOCAL_X_CONNECTED = 72;
    private static final int LOCAL_Y_CONNECTED = 72;
    private static final int LOCAL_WIDTH_CONNECTED = 25;
    private static final int LOCAL_HEIGHT_CONNECTED = 25;
    // Remote video screen position
    private static final int REMOTE_X = 0;
    private static final int REMOTE_Y = 0;
    private static final int REMOTE_WIDTH = 100;
    private static final int REMOTE_HEIGHT = 100;

    private Handler mHandler;

    private CallState callState;

    private enum CallState{
        IDLE, PUBLISHING, PUBLISHED, WAITING_REMOTE_USER, RECEIVING_REMOTE_USER
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_chat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Bundle extras = getIntent().getExtras();
        if (extras == null || !extras.containsKey(Constants.USER_NAME)) {
            ;
            Toast.makeText(this, "Need to pass username to PeerVideoActivity in intent extras (Constants.USER_NAME).",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        this.username      = extras.getString(Constants.USER_NAME, "");
        Log.wtf(TAG, "username: "+username);

        if (extras.containsKey(Constants.CALL_USER)) {
            this.calluser      = extras.getString(Constants.CALL_USER, "");
            Log.wtf(TAG, "callUser: "+calluser);
        }

        this.mCallStatus   = (TextView) findViewById(R.id.call_status);
        TextView prompt   = (TextView) findViewById(R.id.receive_prompt);
        prompt.setText("Receive from " + calluser);

        this.videoView = (GLSurfaceView) findViewById(R.id.gl_surface);
        // Set up the List View for chatting
        RendererCommon.ScalingType scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL;
        VideoRendererGui.setView(videoView, null);

        remoteRender = VideoRendererGui.create( REMOTE_X, REMOTE_Y,
                REMOTE_WIDTH, REMOTE_HEIGHT,
                scalingType, false);
        localRender = VideoRendererGui.create(	LOCAL_X_CONNECTED, LOCAL_Y_CONNECTED,
                LOCAL_WIDTH_CONNECTED, LOCAL_HEIGHT_CONNECTED,
                scalingType, true);

        NBMMediaConfiguration.NBMVideoFormat receiverVideoFormat = new NBMMediaConfiguration.NBMVideoFormat(1280, 720, ImageFormat.YUV_420_888, 30);
        peerConnectionParameters = new NBMMediaConfiguration(   NBMMediaConfiguration.NBMRendererType.OPENGLES,
                NBMMediaConfiguration.NBMAudioCodec.OPUS, 0,
                NBMMediaConfiguration.NBMVideoCodec.VP8, 0,
                receiverVideoFormat,
                NBMMediaConfiguration.NBMCameraPosition.FRONT);
        nbmWebRTCPeer = new NBMWebRTCPeer(peerConnectionParameters, this, localRender, this);
        nbmWebRTCPeer.initialize();

        MainActivity.roomObserver.addObserver(this);

        callState = CallState.IDLE;

        mHandler = new Handler();
        mHandler.postDelayed(publishDelayed, 3000);


        nbmWebRTCPeer.generateOffer("derp");
        callState = CallState.PUBLISHING;

        mCallStatus.setText("Publishing...");
        // If the intent contains a number to dial, call it now that you are connected.
        //  Else, remain listening for a call.
//        if (extras.containsKey(Constants.CALL_USER)) {
//            String callUser = extras.getString(Constants.CALL_USER, "");
//            connectToUser(callUser);
//
//        }


    }

    private Runnable publishDelayed = new Runnable() {

        @Override
        public void run() {
            nbmWebRTCPeer.generateOffer("derp");
        }
    };


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_video_chat, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        nbmWebRTCPeer.stopLocalMedia();

    }

    @Override
    protected void onResume() {
        super.onResume();
        nbmWebRTCPeer.startLocalMedia();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (nbmWebRTCPeer != null){
            nbmWebRTCPeer.close();
        }
    }

    @Override
    public void onBackPressed() {
        if (!this.backPressed){
            this.backPressed = true;
            Toast.makeText(this,"Press back again to end.",Toast.LENGTH_SHORT).show();
            this.backPressedThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(5000);
                        backPressed = false;
                    } catch (InterruptedException e){ Log.d("VCA-oBP","Successfully interrupted"); }
                }
            });
            this.backPressedThread.start();
            return;
        }
        if (this.backPressedThread != null)
            this.backPressedThread.interrupt();
        super.onBackPressed();
    }


    public void hangup(View view) {
        try
        {
            nbmWebRTCPeer.close();
        }
        catch (Exception e){e.printStackTrace();}
        endCall();
    }

    public void receiveFromRemote(View view){

        if (callState == CallState.PUBLISHED){
            nbmWebRTCPeer.generateOffer("remote");

            callState = CallState.WAITING_REMOTE_USER;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mCallStatus.setText("Waiting remote stream...");
                }
            });


        }
//        nbmWebRTCPeer.generateOffer("derp");
    }

    private void endCall() {
        finish();
    }




    @Override
    public void onLocalSdpOfferGenerated(final SessionDescription sessionDescription, NBMPeerConnection nbmPeerConnection) {
        if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
            localSdp = sessionDescription;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (MainActivity.getKurentoRoomAPIInstance() != null) {
                        Log.d(TAG, "Sending " + sessionDescription.type);
                        publishVideoRequestId = ++Constants.id;

//                    String sender = calluser + "_webcam";
//                    MainActivity.getKurentoRoomAPIInstance().sendReceiveVideoFrom(sender, localSdp.description, publishVideoRequestId);

                        MainActivity.getKurentoRoomAPIInstance().sendPublishVideo(localSdp.description, false, publishVideoRequestId);
                    }
                }
            });
        } else { // Asking for remote user video
            remoteSdp = sessionDescription;

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (MainActivity.getKurentoRoomAPIInstance() != null) {
                        Log.d(TAG, "Sending " + sessionDescription.type);
                        publishVideoRequestId = ++Constants.id;

                        String sender = calluser + "_webcam";
                        MainActivity.getKurentoRoomAPIInstance().sendReceiveVideoFrom(sender, remoteSdp.description, publishVideoRequestId);

                    }
                }
            });

        }
    }

    @Override
    public void onLocalSdpAnswerGenerated(SessionDescription sessionDescription, NBMPeerConnection nbmPeerConnection) {

    }

    @Override
    public void onIceCandidate(IceCandidate iceCandidate, NBMPeerConnection nbmPeerConnection) {

        sendIceCandidateRequestId = ++Constants.id;
        if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED){
            MainActivity.getKurentoRoomAPIInstance().sendOnIceCandidate(this.username, iceCandidate.sdp,
                    iceCandidate.sdpMid, Integer.toString(iceCandidate.sdpMLineIndex), sendIceCandidateRequestId);
        } else {
            MainActivity.getKurentoRoomAPIInstance().sendOnIceCandidate(this.calluser, iceCandidate.sdp,
                    iceCandidate.sdpMid, Integer.toString(iceCandidate.sdpMLineIndex), sendIceCandidateRequestId);
        }
    }


    @Override
    public void onIceStatusChanged(PeerConnection.IceConnectionState iceConnectionState, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onIceStatusChanged");
    }

    @Override
    public void onRemoteStreamAdded(MediaStream mediaStream, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onRemoteStreamAdded");
        nbmWebRTCPeer.attachRendererToRemoteStream(remoteRender, mediaStream);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mCallStatus.setText("");
            }
        });

    }

    @Override
    public void onRemoteStreamRemoved(MediaStream mediaStream, NBMPeerConnection nbmPeerConnection) {
        Log.i(TAG, "onRemoteStreamRemoved");
    }

    @Override
    public void onPeerConnectionError(String s) {
        Log.e(TAG, "onPeerConnectionError:" + s);
    }

    @Override
    public void onRoomResponse(RoomResponse response) {
        Log.d(TAG, "OnRoomResponse:" + response);
        if (Integer.valueOf(response.getId()) == publishVideoRequestId){

            SessionDescription sd = new SessionDescription(SessionDescription.Type.ANSWER,
                                                            response.getValue("sdpAnswer"));

            if (callState == CallState.PUBLISHING){
                callState = CallState.PUBLISHED;
                nbmWebRTCPeer.processAnswer(sd, "derp");
            } else if (callState == CallState.WAITING_REMOTE_USER){
                callState = CallState.RECEIVING_REMOTE_USER;
                nbmWebRTCPeer.processAnswer(sd, "remote");
            } else {
                //NOP
            }

        }
    }

    @Override
    public void onRoomError(RoomError error) {
        Log.e(TAG, "OnRoomError:" + error);
    }

    @Override
    public void onRoomNotification(RoomNotification notification) {
        Log.i(TAG, "OnRoomNotification (state=" + callState.toString() + "):" + notification);

        if(notification.getMethod().equals("iceCandidate"))
        {
            Map<String, Object> map = notification.getParams();

            String sdpMid = map.get("sdpMid").toString();
            int sdpMLineIndex = Integer.valueOf(map.get("sdpMLineIndex").toString());
            String sdp = map.get("candidate").toString();

            IceCandidate ic = new IceCandidate(sdpMid, sdpMLineIndex, sdp);


            if (callState == CallState.PUBLISHING || callState == CallState.PUBLISHED) {
                nbmWebRTCPeer.addRemoteIceCandidate(ic, "derp");
            } else {
                nbmWebRTCPeer.addRemoteIceCandidate(ic, "remote");
            }

        }

    }
}