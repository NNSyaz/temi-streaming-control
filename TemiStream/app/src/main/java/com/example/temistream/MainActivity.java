// Enhanced MainActivity.java with Temi SDK 1.136.0
package com.example.temistream;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// WebRTC Imports
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

// Temi Robot SDK 1.136.0 Imports
import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnRobotReadyListener;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.robotemi.sdk.listeners.OnMovementStatusChangedListener;
import com.robotemi.sdk.navigation.listener.OnCurrentPositionChangedListener;
import com.robotemi.sdk.navigation.model.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity implements
        OnRobotReadyListener,
        OnGoToLocationStatusChangedListener,
        OnMovementStatusChangedListener,
        OnCurrentPositionChangedListener {

    private static final String TAG = "TemiStream";
    private static final int PERMISSION_REQUEST_CODE = 1000;

    // UPDATE THIS WITH YOUR NGROK URL
    private static final String WEBSOCKET_URL = "wss://366f607b8176.ngrok-free.app";

    // Tilt angle constants
    private static final int MIN_TILT_ANGLE = -25;  // Maximum down
    private static final int MAX_TILT_ANGLE = 55;   // Maximum up
    private static final int DEFAULT_TILT_STEP = 10; // Default step for tilt adjustments

    // UI Elements
    private Button startButton, stopButton;
    private TextView statusText, robotStatusText;
    private SurfaceViewRenderer localVideoView;

    // WebRTC Components
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private MediaStream localMediaStream;
    private EglBase eglBase;
    private DataChannel dataChannel;

    // WebSocket
    private WebSocket webSocket;
    private OkHttpClient httpClient;

    // Temi Robot
    private Robot robot;
    private boolean robotReady = false;
    private List<String> savedLocations = new ArrayList<>();
    private Map<String, Position> locationPositions = new HashMap<>();
    private int currentTiltAngle = 0;

    // State
    private boolean isStreaming = false;
    private boolean viewerReady = false;
    private boolean isMoving = false;
    private Position currentPosition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
        initRobot();
        initWebRTC();
        setupWebSocket();
    }

    private void initViews() {
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        statusText = findViewById(R.id.statusText);
        robotStatusText = findViewById(R.id.robotStatusText);
        localVideoView = findViewById(R.id.localVideoView);

        startButton.setOnClickListener(v -> startStreaming());
        stopButton.setOnClickListener(v -> stopStreaming());

        updateStatus("Initializing...");
        updateRobotStatus("Connecting to robot...");
        stopButton.setEnabled(false);
    }

    private void initRobot() {
        try {
            robot = Robot.getInstance();

            // Add the supported listeners
            robot.addOnRobotReadyListener(this);
            robot.addOnGoToLocationStatusChangedListener(this);
            robot.addOnMovementStatusChangedListener(this);
            robot.addOnCurrentPositionChangedListener(this);

            updateRobotStatus("Robot SDK initialized - waiting for ready signal");
            Log.d(TAG, "Robot SDK initialized with supported listeners");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Robot SDK", e);
            updateRobotStatus("Robot SDK error: " + e.getMessage());
        }
    }

    private void loadSavedLocations() {
        try {
            if (robot != null && robotReady) {
                // Get all saved locations from the robot
                savedLocations = robot.getLocations();
                Log.d(TAG, "Loaded " + savedLocations.size() + " saved locations");

                // Send updated location list to viewer
                sendLocationList();
                updateRobotStatus("Loaded " + savedLocations.size() + " locations");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading saved locations", e);
        }
    }

    private void sendLocationList() {
        if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            try {
                JSONObject locationData = new JSONObject();
                locationData.put("type", "location_update");

                JSONArray locationsArray = new JSONArray();
                for (String location : savedLocations) {
                    locationsArray.put(location);
                }
                locationData.put("locations", locationsArray);

                // Also include current position if available
                if (currentPosition != null) {
                    JSONObject positionObj = new JSONObject();
                    positionObj.put("x", currentPosition.getX());
                    positionObj.put("y", currentPosition.getY());
                    positionObj.put("yaw", currentPosition.getYaw());
                    positionObj.put("tiltAngle", currentTiltAngle);
                    locationData.put("currentPosition", positionObj);
                }

                String message = locationData.toString();
                DataChannel.Buffer buffer = new DataChannel.Buffer(
                        ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)), false);
                dataChannel.send(buffer);

                Log.d(TAG, "Sent location list to viewer");
            } catch (JSONException e) {
                Log.e(TAG, "Error creating location list message", e);
            }
        }
    }

    private void checkPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE
        };

        List<String> permissionsToRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    private void initWebRTC() {
        try {
            // Initialize EGL context
            eglBase = EglBase.create();

            // Initialize PeerConnectionFactory
            PeerConnectionFactory.InitializationOptions initOptions =
                    PeerConnectionFactory.InitializationOptions.builder(this)
                            .setEnableInternalTracer(true)
                            .createInitializationOptions();
            PeerConnectionFactory.initialize(initOptions);

            PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
            options.disableEncryption = false;
            options.disableNetworkMonitor = false;

            peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                    .setOptions(options)
                    .createPeerConnectionFactory();

            // Initialize local video view
            localVideoView.init(eglBase.getEglBaseContext(), null);
            localVideoView.setMirror(true);

            Log.d(TAG, "WebRTC initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing WebRTC", e);
            updateStatus("WebRTC initialization failed");
        }
    }

    private void setupWebSocket() {
        httpClient = new OkHttpClient();
        Request request = new Request.Builder()
                .url(WEBSOCKET_URL)
                .build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(TAG, "WebSocket connected");
                runOnUiThread(() -> updateStatus("Connected to server"));

                // Register as streamer
                try {
                    JSONObject message = new JSONObject();
                    message.put("type", "streamer");
                    webSocket.send(message.toString());
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating streamer message", e);
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    String type = message.getString("type");

                    Log.d(TAG, "Received message: " + type);

                    switch (type) {
                        case "viewer-ready":
                            viewerReady = true;
                            runOnUiThread(() -> {
                                updateStatus("Viewer connected - ready to stream");
                                startButton.setEnabled(robotReady);
                            });
                            break;

                        case "answer":
                            if (peerConnection != null) {
                                JSONObject answerObj = message.getJSONObject("answer");
                                SessionDescription answer = new SessionDescription(
                                        SessionDescription.Type.ANSWER,
                                        answerObj.getString("sdp")
                                );
                                peerConnection.setRemoteDescription(new CustomSdpObserver() {
                                    @Override
                                    public void onSetSuccess() {
                                        Log.d(TAG, "Remote description set successfully");
                                        runOnUiThread(() -> updateStatus("Video connection established"));
                                        // Send initial location list
                                        sendLocationList();
                                    }

                                    @Override
                                    public void onSetFailure(String error) {
                                        Log.e(TAG, "Failed to set remote description: " + error);
                                    }
                                }, answer);
                            }
                            break;

                        case "candidate":
                            if (peerConnection != null) {
                                JSONObject candidateObj = message.getJSONObject("candidate");
                                IceCandidate candidate = new IceCandidate(
                                        candidateObj.getString("sdpMid"),
                                        candidateObj.getInt("sdpMLineIndex"),
                                        candidateObj.getString("candidate")
                                );
                                peerConnection.addIceCandidate(candidate);
                            }
                            break;
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing message", e);
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, "WebSocket closed: " + reason);
                runOnUiThread(() -> updateStatus("Disconnected from server"));
                viewerReady = false;
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                Log.e(TAG, "WebSocket error", t);
                runOnUiThread(() -> updateStatus("Connection failed"));
                viewerReady = false;
            }
        });
    }

    private void handleDataChannelMessage(DataChannel.Buffer buffer) {
        try {
            byte[] data = new byte[buffer.data.remaining()];
            buffer.data.get(data);
            String messageStr = new String(data, StandardCharsets.UTF_8);
            JSONObject message = new JSONObject(messageStr);

            Log.d(TAG, "Received robot command: " + message.toString());

            if (!message.has("type") || !message.getString("type").equals("robot_command")) {
                return;
            }

            String command = message.getString("command");
            JSONObject params = message.optJSONObject("params");
            String commandId = message.optString("commandId", "");

            runOnUiThread(() -> handleRobotCommand(command, params, commandId));

        } catch (Exception e) {
            Log.e(TAG, "Error handling data channel message", e);
        }
    }

    private void handleRobotCommand(String command, JSONObject params, String commandId) {
        if (!robotReady || robot == null) {
            Log.w(TAG, "Robot not ready for command: " + command);
            sendCommandResponse(commandId, false, "Robot not ready");
            return;
        }

        boolean success = true;
        String responseMessage = "Command executed";

        try {
            Log.d(TAG, "Executing robot command: " + command);

            switch (command) {
                case "move":
                    if (params != null && params.has("direction")) {
                        String direction = params.getString("direction");
                        handleMovement(direction);
                        responseMessage = "Moving " + direction;
                    } else {
                        success = false;
                        responseMessage = "Direction parameter required";
                    }
                    break;

                case "stop":
                    robot.stopMovement();
                    isMoving = false;
                    updateRobotStatus("Movement stopped");
                    responseMessage = "Movement stopped";
                    break;

                case "speak":
                    if (params != null && params.has("text")) {
                        String text = params.getString("text");
                        TtsRequest ttsRequest = TtsRequest.create(text, false);
                        robot.speak(ttsRequest);
                        updateRobotStatus("Speaking: " + text);
                        responseMessage = "Speaking: " + text;
                    } else {
                        success = false;
                        responseMessage = "Text parameter required";
                    }
                    break;

                case "go_to_location":
                    if (params != null && params.has("location")) {
                        String location = params.getString("location");
                        if (savedLocations.contains(location)) {
                            robot.goTo(location);
                            updateRobotStatus("Going to: " + location);
                            responseMessage = "Navigating to " + location;
                        } else {
                            success = false;
                            responseMessage = "Location '" + location + "' not found";
                        }
                    } else {
                        success = false;
                        responseMessage = "Location parameter required";
                    }
                    break;

                case "save_location":
                    String locationName = "custom_location_" + System.currentTimeMillis();
                    if (params != null && params.has("name")) {
                        locationName = params.getString("name");
                    }

                    boolean saved = robot.saveLocation(locationName);
                    if (saved) {
                        // Refresh locations list
                        loadSavedLocations();
                        updateRobotStatus("Location saved: " + locationName);
                        responseMessage = "Location saved: " + locationName;
                    } else {
                        success = false;
                        responseMessage = "Failed to save location";
                    }
                    break;

                case "delete_location":
                    if (params != null && params.has("location")) {
                        String location = params.getString("location");
                        boolean deleted = robot.deleteLocation(location);
                        if (deleted) {
                            // Refresh locations list
                            loadSavedLocations();
                            updateRobotStatus("Location deleted: " + location);
                            responseMessage = "Location deleted: " + location;
                        } else {
                            success = false;
                            responseMessage = "Failed to delete location";
                        }
                    } else {
                        success = false;
                        responseMessage = "Location parameter required";
                    }
                    break;

                case "get_locations":
                    loadSavedLocations();
                    responseMessage = "Location list updated (" + savedLocations.size() + " locations)";
                    break;

                case "follow_me":
                    // Follow me is not available in current SDK, use alternative
                    robot.speak(TtsRequest.create("Follow me mode not available in current SDK", false));
                    updateRobotStatus("Follow me mode not available");
                    responseMessage = "Follow me mode not available in current SDK";
                    success = false;
                    break;

                case "stop_follow":
                    robot.stopMovement();
                    updateRobotStatus("Movement stopped");
                    responseMessage = "Movement stopped";
                    break;

                case "tilt_up":
                    int upAngle = DEFAULT_TILT_STEP;
                    if (params != null && params.has("angle")) {
                        upAngle = params.getInt("angle");
                    }
                    int newUpAngle = Math.min(currentTiltAngle + upAngle, MAX_TILT_ANGLE);
                    robot.tiltAngle(newUpAngle);
                    currentTiltAngle = newUpAngle;
                    updateRobotStatus("Head tilted up to " + newUpAngle + "°");
                    responseMessage = "Head tilted to " + newUpAngle + "°";
                    break;

                case "tilt_down":
                    int downAngle = DEFAULT_TILT_STEP;
                    if (params != null && params.has("angle")) {
                        downAngle = params.getInt("angle");
                    }
                    int newDownAngle = Math.max(currentTiltAngle - downAngle, MIN_TILT_ANGLE);
                    robot.tiltAngle(newDownAngle);
                    currentTiltAngle = newDownAngle;
                    updateRobotStatus("Head tilted down to " + newDownAngle + "°");
                    responseMessage = "Head tilted to " + newDownAngle + "°";
                    break;

                case "tilt_to_angle":
                    if (params != null && params.has("angle")) {
                        int targetAngle = params.getInt("angle");
                        targetAngle = Math.max(MIN_TILT_ANGLE, Math.min(MAX_TILT_ANGLE, targetAngle));
                        robot.tiltAngle(targetAngle);
                        currentTiltAngle = targetAngle;
                        updateRobotStatus("Head tilted to " + targetAngle + "°");
                        responseMessage = "Head tilted to " + targetAngle + "°";
                    } else {
                        success = false;
                        responseMessage = "Angle parameter required";
                    }
                    break;

                case "reset_tilt":
                    robot.tiltAngle(0);
                    currentTiltAngle = 0;
                    updateRobotStatus("Head tilt reset to center");
                    responseMessage = "Head tilt reset to center";
                    break;

                case "emergency_stop":
                    robot.stopMovement();
                    robot.speak(TtsRequest.create("Emergency stop activated", true));
                    updateRobotStatus("EMERGENCY STOP ACTIVATED");
                    responseMessage = "Emergency stop activated";
                    break;

                case "turn_around":
                    robot.turnBy(180);
                    updateRobotStatus("Turning around 180°");
                    responseMessage = "Turning around";
                    break;

                case "turn_by":
                    if (params != null && params.has("degrees")) {
                        int degrees = params.getInt("degrees");
                        robot.turnBy(degrees);
                        updateRobotStatus("Turning by " + degrees + "°");
                        responseMessage = "Turning by " + degrees + "°";
                    } else {
                        success = false;
                        responseMessage = "Degrees parameter required";
                    }
                    break;

                case "go_home":
                    if (savedLocations.contains("home base")) {
                        robot.goTo("home base");
                        updateRobotStatus("Going home");
                        responseMessage = "Going home";
                    } else {
                        // Try to go to first saved location
                        if (!savedLocations.isEmpty()) {
                            String homeLocation = savedLocations.get(0);
                            robot.goTo(homeLocation);
                            updateRobotStatus("Going to " + homeLocation);
                            responseMessage = "Going to " + homeLocation;
                        } else {
                            success = false;
                            responseMessage = "No home location saved";
                        }
                    }
                    break;

                case "get_battery_info":
                    // Get battery information (simplified since getBatteryLevel is not available)
                    try {
                        // Use alternative method if available
                        updateRobotStatus("Battery info requested");
                        responseMessage = "Battery info requested - check robot display";
                    } catch (Exception e) {
                        success = false;
                        responseMessage = "Could not get battery information";
                    }
                    break;

                case "set_volume":
                    if (params != null && params.has("level")) {
                        int volume = params.getInt("level");
                        volume = Math.max(0, Math.min(100, volume)); // Clamp to 0-100
                        robot.setVolume(volume);
                        updateRobotStatus("Volume set to " + volume + "%");
                        responseMessage = "Volume set to " + volume + "%";
                    } else {
                        success = false;
                        responseMessage = "Volume level parameter required";
                    }
                    break;

                default:
                    Log.w(TAG, "Unknown command: " + command);
                    success = false;
                    responseMessage = "Unknown command: " + command;
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing robot command: " + command, e);
            updateRobotStatus("Command error: " + e.getMessage());
            success = false;
            responseMessage = "Error: " + e.getMessage();
        }

        sendCommandResponse(commandId, success, responseMessage);
    }

    private void sendCommandResponse(String commandId, boolean success, String message) {
        if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN && !commandId.isEmpty()) {
            try {
                JSONObject response = new JSONObject();
                response.put("type", "robot_response");
                response.put("commandId", commandId);
                response.put("success", success);
                response.put("message", message);
                response.put("timestamp", System.currentTimeMillis());

                String responseStr = response.toString();
                DataChannel.Buffer buffer = new DataChannel.Buffer(
                        ByteBuffer.wrap(responseStr.getBytes(StandardCharsets.UTF_8)), false);
                dataChannel.send(buffer);

                Log.d(TAG, "Sent command response: " + success + " - " + message);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating command response", e);
            }
        }
    }

    private void handleMovement(String direction) {
        float speed = 0.5f; // Moderate speed

        switch (direction) {
            case "forward":
                robot.skidJoy(speed, 0.0f);
                break;
            case "backward":
                robot.skidJoy(-speed, 0.0f);
                break;
            case "left":
                robot.skidJoy(0.0f, speed);
                break;
            case "right":
                robot.skidJoy(0.0f, -speed);
                break;
            case "turn_left":
                robot.turnBy(-45); // Smaller turn increments for better control
                return; // Don't set isMoving for turns
            case "turn_right":
                robot.turnBy(45); // Smaller turn increments for better control
                return; // Don't set isMoving for turns
        }

        isMoving = true;
        updateRobotStatus("Moving " + direction);
    }

    // Continue with the rest of the methods...
    private void startStreaming() {
        if (isStreaming) return;

        updateStatus("Starting stream...");

        try {
            // Create peer connection
            List<PeerConnection.IceServer> iceServers = new ArrayList<>();
            iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
            iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());

            PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
            rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

            peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnectionObserver());

            if (peerConnection == null) {
                updateStatus("Failed to create peer connection");
                return;
            }

            // Create data channel for robot control
            DataChannel.Init dataChannelInit = new DataChannel.Init();
            dataChannelInit.ordered = true;
            dataChannelInit.negotiated = false;
            dataChannel = peerConnection.createDataChannel("robotControl", dataChannelInit);

            dataChannel.registerObserver(new DataChannel.Observer() {
                @Override
                public void onBufferedAmountChange(long l) {
                    // Handle buffered amount change
                }

                @Override
                public void onStateChange() {
                    Log.d(TAG, "Data channel state: " + dataChannel.state());
                    if (dataChannel.state() == DataChannel.State.OPEN) {
                        runOnUiThread(() -> updateStatus("Robot control channel ready"));
                        // Send initial location list when channel opens
                        loadSavedLocations();
                    }
                }

                @Override
                public void onMessage(DataChannel.Buffer buffer) {
                    // Handle incoming messages from viewer
                    handleDataChannelMessage(buffer);
                }
            });

            // Create video capturer
            videoCapturer = createCameraCapturer();
            if (videoCapturer == null) {
                updateStatus("Failed to create camera capturer");
                return;
            }

            // Create video source and track
            SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
            videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
            videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());
            videoCapturer.startCapture(1280, 720, 30);

            VideoTrack videoTrack = peerConnectionFactory.createVideoTrack("video_track", videoSource);
            videoTrack.addSink(localVideoView);

            // Create audio source and track
            MediaConstraints audioConstraints = new MediaConstraints();
            audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
            AudioTrack audioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource);

            // Create media stream and add tracks
            localMediaStream = peerConnectionFactory.createLocalMediaStream("local_stream");
            localMediaStream.addTrack(videoTrack);
            localMediaStream.addTrack(audioTrack);

            // Add tracks to peer connection
            List<String> streamIds = new ArrayList<>();
            streamIds.add("local_stream");
            peerConnection.addTrack(videoTrack, streamIds);
            peerConnection.addTrack(audioTrack, streamIds);

            // Create offer
            MediaConstraints constraints = new MediaConstraints();
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

            peerConnection.createOffer(new CustomSdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    peerConnection.setLocalDescription(new CustomSdpObserver() {
                        @Override
                        public void onSetSuccess() {
                            // Send offer via WebSocket
                            try {
                                JSONObject offerMessage = new JSONObject();
                                offerMessage.put("type", "offer");
                                JSONObject offer = new JSONObject();
                                offer.put("type", sessionDescription.type.canonicalForm());
                                offer.put("sdp", sessionDescription.description);
                                offerMessage.put("offer", offer);

                                webSocket.send(offerMessage.toString());
                                runOnUiThread(() -> updateStatus("Offer sent, waiting for viewer..."));
                            } catch (JSONException e) {
                                Log.e(TAG, "Error creating offer message", e);
                            }
                        }

                        @Override
                        public void onSetFailure(String error) {
                            Log.e(TAG, "Failed to set local description: " + error);
                        }
                    }, sessionDescription);
                }

                @Override
                public void onCreateFailure(String error) {
                    Log.e(TAG, "Failed to create offer: " + error);
                    runOnUiThread(() -> updateStatus("Failed to create offer"));
                }
            }, constraints);

            isStreaming = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);

        } catch (Exception e) {
            Log.e(TAG, "Error starting stream", e);
            updateStatus("Failed to start stream: " + e.getMessage());
        }
    }

    private void stopStreaming() {
        if (!isStreaming) return;

        updateStatus("Stopping stream...");

        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping video capturer", e);
            }
            videoCapturer.dispose();
            videoCapturer = null;
        }

        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }

        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }

        if (dataChannel != null) {
            dataChannel.close();
            dataChannel = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        if (localMediaStream != null) {
            localMediaStream.dispose();
            localMediaStream = null;
        }

        // Stop robot movement if active
        if (robot != null && isMoving) {
            robot.stopMovement();
            isMoving = false;
        }

        isStreaming = false;
        startButton.setEnabled(viewerReady && robotReady);
        stopButton.setEnabled(false);
        updateStatus("Stream stopped");
    }

    private VideoCapturer createCameraCapturer() {
        CameraEnumerator enumerator;
        if (Camera2Enumerator.isSupported(this)) {
            enumerator = new Camera2Enumerator(this);
        } else {
            enumerator = new Camera1Enumerator(true);
        }

        final String[] deviceNames = enumerator.getDeviceNames();

        // Try to find front camera first
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // If no front camera, use back camera
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> {
            statusText.setText(status);
            Log.d(TAG, "Status: " + status);
        });
    }

    private void updateRobotStatus(String status) {
        runOnUiThread(() -> {
            if (robotStatusText != null) {
                robotStatusText.setText("Robot: " + status);
            }
            Log.d(TAG, "Robot Status: " + status);
        });
    }

    // Temi Robot Listeners
    @Override
    public void onRobotReady(boolean isReady) {
        robotReady = isReady;
        if (isReady) {
            updateRobotStatus("Robot ready - loading locations...");
            runOnUiThread(() -> startButton.setEnabled(viewerReady));

            // Load saved locations when robot becomes ready
            loadSavedLocations();

            // Get current tilt angle - use a safe default if not available
            try {
                // Note: getTiltAngle() may not be available in all SDK versions
                // currentTiltAngle = robot.getTiltAngle();
                currentTiltAngle = 0; // Use default for now
                Log.d(TAG, "Current tilt angle set to default: " + currentTiltAngle);
            } catch (Exception e) {
                Log.w(TAG, "Could not get current tilt angle, using default", e);
                currentTiltAngle = 0;
            }
        } else {
            updateRobotStatus("Robot not ready");
            runOnUiThread(() -> startButton.setEnabled(false));
        }
        Log.d(TAG, "Robot ready: " + isReady);
    }

    @Override
    public void onGoToLocationStatusChanged(String location, String status, int descriptionId, String description) {
        updateRobotStatus("Navigation: " + status + " to " + location);
        Log.d(TAG, "Navigation status: " + status + " to " + location + " - " + description);

        // Send status update to viewer
        if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            try {
                JSONObject statusUpdate = new JSONObject();
                statusUpdate.put("type", "navigation_status");
                statusUpdate.put("location", location);
                statusUpdate.put("status", status);
                statusUpdate.put("description", description);
                statusUpdate.put("timestamp", System.currentTimeMillis());

                String message = statusUpdate.toString();
                DataChannel.Buffer buffer = new DataChannel.Buffer(
                        ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)), false);
                dataChannel.send(buffer);
            } catch (JSONException e) {
                Log.e(TAG, "Error sending navigation status", e);
            }
        }
    }

    @Override
    public void onMovementStatusChanged(String type, String status) {
        if (status.equals("idle")) {
            isMoving = false;
        }
        updateRobotStatus("Movement: " + type + " - " + status);
        Log.d(TAG, "Movement status: " + type + " - " + status);
    }

    @Override
    public void onCurrentPositionChanged(Position position) {
        currentPosition = position;
        Log.d(TAG, "Position changed: x=" + position.getX() + ", y=" + position.getY() + ", yaw=" + position.getYaw());

        // Send position update to viewer
        if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            try {
                JSONObject positionUpdate = new JSONObject();
                positionUpdate.put("type", "position_update");
                positionUpdate.put("x", position.getX());
                positionUpdate.put("y", position.getY());
                positionUpdate.put("yaw", position.getYaw());
                positionUpdate.put("tiltAngle", currentTiltAngle);
                positionUpdate.put("timestamp", System.currentTimeMillis());

                String message = positionUpdate.toString();
                DataChannel.Buffer buffer = new DataChannel.Buffer(
                        ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)), false);
                dataChannel.send(buffer);
            } catch (JSONException e) {
                Log.e(TAG, "Error sending position update", e);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStreaming();

        if (webSocket != null) {
            webSocket.close(1000, "Activity destroyed");
        }

        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
        }

        if (eglBase != null) {
            eglBase.release();
        }

        // Clean up robot listeners
        if (robot != null) {
            robot.removeOnRobotReadyListener(this);
            robot.removeOnGoToLocationStatusChangedListener(this);
            robot.removeOnMovementStatusChangedListener(this);
            robot.removeOnCurrentPositionChangedListener(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    // PeerConnection Observer
    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(TAG, "Signaling state changed: " + signalingState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "ICE connection state changed: " + iceConnectionState);
            runOnUiThread(() -> {
                switch (iceConnectionState) {
                    case CONNECTED:
                        updateStatus("Streaming live! Robot control active");
                        break;
                    case DISCONNECTED:
                        updateStatus("Stream disconnected");
                        break;
                    case FAILED:
                        updateStatus("Connection failed");
                        break;
                    case CHECKING:
                        updateStatus("Establishing connection...");
                        break;
                }
            });
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d(TAG, "ICE connection receiving changed: " + receiving);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, "ICE gathering state changed: " + iceGatheringState);
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.d(TAG, "New ICE candidate: " + iceCandidate.toString());

            try {
                JSONObject candidateMessage = new JSONObject();
                candidateMessage.put("type", "candidate");
                JSONObject candidate = new JSONObject();
                candidate.put("sdpMid", iceCandidate.sdpMid);
                candidate.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                candidate.put("candidate", iceCandidate.sdp);
                candidateMessage.put("candidate", candidate);

                webSocket.send(candidateMessage.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Error creating candidate message", e);
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            Log.d(TAG, "ICE candidates removed");
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "Stream added");
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.d(TAG, "Stream removed");
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Log.d(TAG, "Data channel created from remote");
            // Handle incoming data channel from viewer
            dataChannel.registerObserver(new DataChannel.Observer() {
                @Override
                public void onBufferedAmountChange(long l) {}

                @Override
                public void onStateChange() {
                    Log.d(TAG, "Incoming data channel state: " + dataChannel.state());
                }

                @Override
                public void onMessage(DataChannel.Buffer buffer) {
                    handleDataChannelMessage(buffer);
                }
            });
        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed");
        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
            Log.d(TAG, "Track added");
        }
    }

    // Custom SDP Observer implementation
    private abstract class CustomSdpObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            // Override in implementation
        }

        @Override
        public void onSetSuccess() {
            // Override in implementation
        }

        @Override
        public void onCreateFailure(String error) {
            Log.e(TAG, "SDP create failure: " + error);
        }

        @Override
        public void onSetFailure(String error) {
            Log.e(TAG, "SDP set failure: " + error);
        }
    }
}