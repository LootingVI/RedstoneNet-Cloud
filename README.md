# RedstoneNet CloudSystem ⚡

A high-performance, modular, and professional-grade Cloud Infrastructure for Minecraft server networks. This system automates the lifecycle of game servers (Paper/Spigot) and proxies (Velocity/BungeeCord) with real-time performance monitoring and an integrated web dashboard.

## 🚀 Key Features

*   **Dynamic Server Management**: Automatically start, stop, and scale server instances based on demand.
*   **Multi-Platform Support**: Seamlessly manages Paper, Spigot, Velocity, and BungeeCord.
*   **Real-time Performance Monitoring**: Track TPS, CPU usage, and RAM consumption for every process.
*   **Web-Based Dashboard**: Manage your entire network through a modern, responsive web interface.
*   **Automated Bedrock Integration**: Built-in support for GeyserMC and Floodgate, including automated key synchronization.
*   **Integrated Permission System**: Manage roles and permissions across the whole network.
*   **Automated Backup System**: Robust backup logic for static server environments.
*   **Global Anti-VPN Protection**: Real-time identification and blocking of VPN/Proxy connections at the proxy level.
*   **Auto-Scaling 📈**: Automatically scale up server instances based on player count thresholds, and scale down when empty.
*   **ResourcePack CDN 📦**: Centralized HTTP distribution of server resource packs with automatic SHA-1 generation and in-game application.
*   **Modular Architecture**: Separated API, Node, and Plugin components for maximum flexibility.

---

## 🏗️ Project Structure

The project is structured into three main Maven modules:

*   **`cloud-api`**: The core communication protocol and shared data models.
*   **`cloud-node`**: The central controller that manages processes, the web server, and network logic.
*   **`cloud-plugin`**: The bridge plugin for both Bukkit/Spigot and Velocity/BungeeCord to connect to the node.

---

## 🛠️ Building the System

The project uses Maven for dependency management and building.

### Prerequisites
*   Java 17 or higher
*   Maven 3.6 or higher

### Build Instruction
Run the following command in the root directory:
```bash
mvn clean package -pl cloud-node,cloud-api,cloud-plugin -am
```
The compiled JAR files will be located in the `target/` directories of each module.

---

## 🚦 Setup Guide

Follow these steps to set up your first Cloud Network:

### 1. Node Initialization
1.  Run the Node for the first time: `java -jar cloud-node.jar`
2.  The Cloud will create a `config.properties`.
3.  Set your `host` (e.g., `127.0.0.1`) and `port` (e.g., `3000`).
4.  Leave `authKey` empty for auto-generation on next start.

### 2. Software Preparation
1.  Use the `software` command to download Paper or Velocity:
    *   `software paper 1.20.4`
    *   `software velocity 3.3.0`
2.  Wait for the download to finish (check logs).

### 3. Create Server Groups
Create groups for your network using the `create` command:
*   **Lobby**: `create Lobby 1024 true false paper.jar true 25566 1`
*   **Proxy**: `create Proxy 512 true true velocity.jar false 25565 1`
*(Template: name | memory | static | proxy | jar | bedrock | port | minOnline)*

### 4. Configure Static Settings
Refresh the cloud or edit `settings.properties` to set global MOTDs and Tablists. These will be synced to all servers automatically.

### 5. Web Dashboard
1.  Access `http://127.0.0.1:3030`.
2.  Type `webtokens create` in the cloud console.
3.  Copy and paste the token to log in.

### 6. Anti-VPN Features
To enable the built-in Anti-VPN system:
1.  Open `settings.properties` in the root folder.
2.  Set `vpn.block.enabled` to `true`.
3.  (Optional) Add your API key from `proxycheck.io` to `vpn.api.key` for higher rate limits.
4.  Custom kick messages can be set via `vpn.kick.message`.
### 7. Performance & Auto-Restart
The system includes a performance-based auto-restart logic to ensure stability:
1.  Open `settings.properties` in the root folder.
2.  Set `autorestart.tps.enabled` to `true`.
3.  Modify `autorestart.tps.threshold` (default `10.0`).
4.  Servers whose TPS falls below this threshold will be automatically restarted by the Cloud.

### 8. Auto-Scaling & Resource Packs
1.  **Auto-Scaling**: Configurable completely via the Web Dashboard slider. If a server reaches the threshold % of players, a new one spins up (up to the configured max limit).
2.  **Resource Packs**: Drop your `.zip` files into `local/resourcepacks/`. Select them in the Web Dashboard for any group. The cloud automatically calculates the SHA-1 hash and applies it internally to players when they join.

---

## 💻 Usage

### Starting the Cloud Node
1. Move the `cloud-node.jar` to your desired installation directory.
2. Run it via: `java -jar cloud-node.jar`
3. Upon first start, it will generate a `config.properties`. Configure the `host`, `port`, and `authKey`.

### Cloud Commands
Type `help` in the console to see all available commands:
*   `start <Group> <Count>` - Start instances of a specific group.
*   `stopserver <Name>` - Stop a specific server instance.
*   `create <Name> <MB> <isStatic> <isProxy> <JAR> <Bedrock> [Port] [minOnline]` - Create a new server group.
*   `maintenance <true/false>` - Toggle global maintenance mode.
*   `backup <ServerName>` - Trigger a manual backup.
*   `webtokens create` - Generate a new token for dashboard access.

---

## 📊 Web Dashboard
The dashboard is accessible by default at `http://127.0.0.1:3030`.
To log in, you must generate a token via the cloud console using `webtokens create`.

---

## 📝 License
This project is for internal use within the RedstoneNet network. All rights reserved.
