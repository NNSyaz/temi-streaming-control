const WebSocket = require('ws');
const express = require('express');
const path = require('path');
const app = express();
const server = require('http').createServer(app);

// Serve static files (for web viewer)
app.use(express.static(path.join(__dirname, 'public')));

const wss = new WebSocket.Server({ server });

let viewer = null;
let streamer = null;
let robotCommands = [];
let robotStatus = {
    connected: false,
    streaming: false,
    position: null,
    battery: null,
    lastCommand: null,
    commandCount: 0
};

console.log('Starting Enhanced WebRTC Signaling Server with Robot Control...');

wss.on('connection', (ws, req) => {
    const clientIP = req.connection.remoteAddress;
    console.log('New WebSocket connection from:', clientIP);
    
    ws.on('message', (message) => {
        try {
            const data = JSON.parse(message);
            console.log(`[${new Date().toISOString()}] Received:`, data.type);
            
            switch(data.type) {
                case 'viewer':
                    viewer = ws;
                    console.log('Viewer connected from:', clientIP);
                    
                    // Send current robot status to viewer
                    ws.send(JSON.stringify({
                        type: 'robot_status',
                        status: robotStatus
                    }));
                    
                    // Notify streamer that viewer is ready
                    if (streamer && streamer.readyState === WebSocket.OPEN) {
                        streamer.send(JSON.stringify({ type: 'viewer-ready' }));
                        robotStatus.connected = true;
                    }
                    break;
                    
                case 'streamer':
                    streamer = ws;
                    console.log('Streamer (Robot) connected from:', clientIP);
                    robotStatus.connected = true;
                    robotStatus.streaming = false;
                    
                    // Notify viewer that streamer is ready
                    if (viewer && viewer.readyState === WebSocket.OPEN) {
                        viewer.send(JSON.stringify({ type: 'streamer-ready' }));
                    }
                    break;
                    
                case 'offer':
                    console.log('Relaying offer from streamer to viewer');
                    robotStatus.streaming = true;
                    
                    if (viewer && viewer.readyState === WebSocket.OPEN) {
                        viewer.send(JSON.stringify(data));
                    } else {
                        console.warn('No viewer available to receive offer');
                    }
                    break;
                    
                case 'answer':
                    console.log('Relaying answer from viewer to streamer');
                    if (streamer && streamer.readyState === WebSocket.OPEN) {
                        streamer.send(JSON.stringify(data));
                    } else {
                        console.warn('No streamer available to receive answer');
                    }
                    break;
                    
                case 'candidate':
                    console.log('Relaying ICE candidate');
                    if (ws === viewer && streamer && streamer.readyState === WebSocket.OPEN) {
                        streamer.send(JSON.stringify(data));
                    } else if (ws === streamer && viewer && viewer.readyState === WebSocket.OPEN) {
                        viewer.send(JSON.stringify(data));
                    }
                    break;
                    
                case 'robot_command':
                    console.log('Robot command received:', data.command);
                    robotStatus.lastCommand = {
                        command: data.command,
                        params: data.params,
                        timestamp: data.timestamp || Date.now()
                    };
                    robotStatus.commandCount++;
                    
                    // Store command for logging/debugging
                    robotCommands.push({
                        command: data.command,
                        params: data.params,
                        timestamp: Date.now(),
                        source: 'viewer'
                    });
                    
                    // Keep only last 100 commands
                    if (robotCommands.length > 100) {
                        robotCommands = robotCommands.slice(-100);
                    }
                    
                    // Forward command to streamer (robot)
                    if (streamer && streamer.readyState === WebSocket.OPEN) {
                        streamer.send(JSON.stringify(data));
                    } else {
                        console.warn('No robot available to receive command');
                        
                        // Send error back to viewer
                        if (viewer && viewer.readyState === WebSocket.OPEN) {
                            viewer.send(JSON.stringify({
                                type: 'robot_response',
                                success: false,
                                error: 'Robot not connected',
                                commandId: data.commandId
                            }));
                        }
                    }
                    break;
                    
                case 'robot_response':
                    console.log('Robot response:', data);
                    // Forward response to viewer
                    if (viewer && viewer.readyState === WebSocket.OPEN) {
                        viewer.send(JSON.stringify(data));
                    }
                    break;
                    
                case 'robot_status_update':
                    console.log('Robot status update:', data.status);
                    // Update our robot status
                    Object.assign(robotStatus, data.status);
                    
                    // Forward status to viewer
                    if (viewer && viewer.readyState === WebSocket.OPEN) {
                        viewer.send(JSON.stringify({
                            type: 'robot_status',
                            status: robotStatus
                        }));
                    }
                    break;
                    
                case 'ping':
                    // Respond to ping with pong
                    ws.send(JSON.stringify({ type: 'pong', timestamp: Date.now() }));
                    break;
                    
                case 'get_robot_status':
                    // Send current robot status
                    ws.send(JSON.stringify({
                        type: 'robot_status',
                        status: robotStatus
                    }));
                    break;
                    
                case 'get_command_history':
                    // Send recent command history
                    ws.send(JSON.stringify({
                        type: 'command_history',
                        commands: robotCommands.slice(-20) // Last 20 commands
                    }));
                    break;
                    
                default:
                    console.log('Unknown message type:', data.type);
            }
        } catch (error) {
            console.error('Error parsing message:', error);
            console.error('Raw message:', message.toString());
        }
    });
    
    ws.on('close', (code, reason) => {
        console.log(`WebSocket connection closed: ${code} - ${reason}`);
        
        if (ws === viewer) {
            viewer = null;
            robotStatus.connected = false;
            console.log('Viewer disconnected');
            
            // Notify streamer that viewer disconnected
            if (streamer && streamer.readyState === WebSocket.OPEN) {
                streamer.send(JSON.stringify({ type: 'viewer-disconnected' }));
            }
        }
        
        if (ws === streamer) {
            streamer = null;
            robotStatus.connected = false;
            robotStatus.streaming = false;
            console.log('Streamer (Robot) disconnected');
            
            // Notify viewer that robot disconnected
            if (viewer && viewer.readyState === WebSocket.OPEN) {
                viewer.send(JSON.stringify({ type: 'streamer-disconnected' }));
            }
        }
    });
    
    ws.on('error', (error) => {
        console.error('WebSocket error:', error);
    });
    
    // Send initial connection confirmation
    ws.send(JSON.stringify({
        type: 'connection_established',
        timestamp: Date.now(),
        serverVersion: '2.0.0'
    }));
});

// Health check endpoint with detailed status
app.get('/health', (req, res) => {
    res.json({
        status: 'ok',
        timestamp: new Date().toISOString(),
        connections: {
            viewer: viewer ? 'connected' : 'disconnected',
            streamer: streamer ? 'connected' : 'disconnected'
        },
        robotStatus: robotStatus,
        recentCommands: robotCommands.slice(-5), // Last 5 commands
        uptime: process.uptime()
    });
});

// Robot status endpoint
app.get('/robot/status', (req, res) => {
    res.json({
        status: robotStatus,
        lastUpdate: new Date().toISOString()
    });
});

// Command history endpoint
app.get('/robot/commands', (req, res) => {
    const limit = parseInt(req.query.limit) || 50;
    res.json({
        commands: robotCommands.slice(-limit),
        total: robotCommands.length
    });
});

// Emergency stop endpoint
app.post('/robot/emergency-stop', (req, res) => {
    if (streamer && streamer.readyState === WebSocket.OPEN) {
        const emergencyCommand = {
            type: 'robot_command',
            command: 'emergency_stop',
            params: {},
            timestamp: Date.now(),
            priority: 'emergency'
        };
        
        streamer.send(JSON.stringify(emergencyCommand));
        
        // Log emergency command
        robotCommands.push({
            command: 'emergency_stop',
            params: {},
            timestamp: Date.now(),
            source: 'api_emergency'
        });
        
        res.json({
            success: true,
            message: 'Emergency stop command sent to robot'
        });
    } else {
        res.status(503).json({
            success: false,
            error: 'Robot not connected'
        });
    }
});

// Send command endpoint (for external integrations)
app.post('/robot/command', express.json(), (req, res) => {
    const { command, params = {} } = req.body;
    
    if (!command) {
        return res.status(400).json({
            success: false,
            error: 'Command is required'
        });
    }
    
    if (streamer && streamer.readyState === WebSocket.OPEN) {
        const commandData = {
            type: 'robot_command',
            command: command,
            params: params,
            timestamp: Date.now(),
            source: 'api'
        };
        
        streamer.send(JSON.stringify(commandData));
        
        // Log command
        robotCommands.push({
            command: command,
            params: params,
            timestamp: Date.now(),
            source: 'api'
        });
        
        res.json({
            success: true,
            message: `Command "${command}" sent to robot`
        });
    } else {
        res.status(503).json({
            success: false,
            error: 'Robot not connected'
        });
    }
});

// Serve the enhanced web viewer
app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// Statistics endpoint
app.get('/stats', (req, res) => {
    const now = Date.now();
    const oneHourAgo = now - (60 * 60 * 1000);
    
    const recentCommands = robotCommands.filter(cmd => cmd.timestamp > oneHourAgo);
    const commandStats = {};
    
    recentCommands.forEach(cmd => {
        commandStats[cmd.command] = (commandStats[cmd.command] || 0) + 1;
    });
    
    res.json({
        totalCommands: robotCommands.length,
        commandsLastHour: recentCommands.length,
        commandBreakdown: commandStats,
        currentStatus: robotStatus,
        serverUptime: process.uptime(),
        connections: {
            viewer: !!viewer,
            streamer: !!streamer
        }
    });
});

// Error handling middleware
app.use((err, req, res, next) => {
    console.error('Express error:', err);
    res.status(500).json({
        error: 'Internal server error',
        message: err.message
    });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
    console.log(`Enhanced Signaling Server running on port ${PORT}`);
    console.log(`Web viewer: http://localhost:${PORT}`);
    console.log(`Health check: http://localhost:${PORT}/health`);
    console.log(`Robot status: http://localhost:${PORT}/robot/status`);
    console.log(`Emergency stop: POST http://localhost:${PORT}/robot/emergency-stop`);
    console.log('Run "ngrok http 3000" to expose this server publicly');
    console.log('');
    console.log('Available endpoints:');
    console.log('  GET  /health - Server and connection health');
    console.log('  GET  /robot/status - Current robot status');
    console.log('  GET  /robot/commands - Command history');
    console.log('  GET  /stats - Usage statistics');
    console.log('  POST /robot/emergency-stop - Emergency stop');
    console.log('  POST /robot/command - Send command to robot');
});

// Graceful shutdown
process.on('SIGTERM', () => {
    console.log('SIGTERM received, shutting down gracefully...');
    server.close(() => {
        console.log('Server closed');
        process.exit(0);
    });
});

process.on('SIGINT', () => {
    console.log('SIGINT received, shutting down gracefully...');
    server.close(() => {
        console.log('Server closed');
        process.exit(0);
    });
});