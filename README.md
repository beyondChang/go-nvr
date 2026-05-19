# go-nvr

基于 Go 的 NVR（网络视频录像机）后端服务。

## 编译

```bash
go build -o go-nvr.exe .
```

或直接运行（不生成可执行文件）：

```bash
go run .
```

## 启动

### 基本启动

```bash
go-nvr.exe
```

默认监听 `:9090`，数据目录为当前目录下的 `data/`。

### 指定监听端口

```bash
go-nvr.exe --port 9090          # 监听 :9090
go-nvr.exe --port :9090         # 同上
go-nvr.exe --port 0.0.0.0:9090  # 监听所有网卡的 9090 端口
go-nvr.exe --port 192.168.1.100:9090  # 监听指定 IP
```

### 指定数据目录

```bash
go-nvr.exe --data /mnt/nvr-data
```

数据目录用于存放 SQLite 数据库文件、录像片段、HLS 切片等。

### 组合使用

```bash
go-nvr.exe --port 8080 --data /mnt/nvr-data
```

### 查看版本

```bash
go-nvr.exe --version
```

## 健康检查

```bash
go-nvr.exe health
```

默认检查 `http://localhost:9090/api/health`，可指定地址：

```bash
go-nvr.exe health --addr :9090
go-nvr.exe health --addr 192.168.1.100:9090
```

## 首次使用

启动后，服务会自动初始化 SQLite 数据库并创建默认 admin 用户。打开浏览器访问：

```
http://localhost:9090
```

进入 Web 管理界面，默认管理员账号密码在首次启动时自动生成并打印在日志中。
