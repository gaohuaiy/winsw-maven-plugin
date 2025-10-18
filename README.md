# WinSW Maven Plugin

这是一个Maven插件，用于将Java应用程序打包为Windows服务可执行文件，基于开源项目[WinSW (Windows Service Wrapper)](https://github.com/winsw/winsw)。

## 功能特点

- 自动下载WinSW可执行文件
- 为您的Java应用程序生成Windows服务配置
- 将应用程序JAR复制到输出目录
- 生成可直接部署的Windows服务可执行文件
- 支持自定义日志路径，包括相对路径（%BASE%变量）
- 自动配置日志输出到`%BASE%\..\logs\out`目录
- 集成RunawayProcessKiller扩展，防止进程失控
- 自动复制lib和resources目录到输出位置
- **新增: 自动清理上一次构建产物** - 在生成新的构建产物前，会自动删除之前的可执行文件和配置文件，确保每次构建都是全新的产物

## 安装

首先，构建并安装插件到您的本地Maven仓库：

```bash
mvn clean install
```

## 使用方法

### 方法1：在pom.xml中配置

在您的Java项目的`pom.xml`中添加以下插件配置：

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.winsw</groupId>
            <artifactId>winsw-maven-plugin</artifactId>
            <version>1.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals>
                        <goal>generate-exe</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <!-- 可选配置 -->
                <serviceId>my-application-service</serviceId>
                <serviceName>My Application Service</serviceName>
                <serviceDescription>My Java Application as Windows Service</serviceDescription>
                <javaPath>C:\Program Files\Java\jdk1.8.0_202\bin\java.exe</javaPath>
                <additionalJvmOptions>-Xmx512m -XX:+UseG1GC</additionalJvmOptions>
                <additionalAppArgs>--spring.profiles.active=prod</additionalAppArgs>
                <winswVersion>2.12.0</winswVersion>
                <!-- 自定义日志路径，支持相对路径 -->
                <logPath>%BASE%\..\logs</logPath>
            </configuration>
        </plugin>
    </plugins>
</build>```

### 方法2：通过命令行运行

您也可以直接通过命令行运行插件，指定所需参数：

```bash
# 基本用法
mvn winsw:generate-exe -DprojectName=my-service

# 使用自定义日志路径（绝对路径）
mvn winsw:generate-exe -DprojectName=my-service -DlogPath=C:\path\to\logs

# 使用相对路径（基于可执行文件目录）
mvn winsw:generate-exe -DprojectName=my-service -DlogPath="%BASE%\..\logs"```

## 配置选项

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| serviceId | Windows服务的唯一标识符 | ${project.artifactId} |
| serviceName | 服务显示名称 | ${project.name} |
| serviceDescription | 服务描述 | ${project.description} |
| outputDir | 输出目录（存放exe和配置文件） | ${project.build.directory}/bin |
| javaPath | Java可执行文件路径 | java |
| additionalJvmOptions | 额外的JVM选项 | 无 |
| additionalAppArgs | 应用程序额外参数 | 无 |
| winswVersion | WinSW版本 | 2.12.0 |
| winswDownloadUrl | WinSW下载URL | https://github.com/winsw/winsw/releases/download/v${winswVersion}/WinSW.NET4.exe |
| jarPath | 应用程序JAR路径 | ${project.build.directory}/${project.build.finalName}.jar |
| logPath | 日志输出路径，支持%BASE%变量 | %BASE%\..\logs\out |
| copyLibDir | 是否复制lib目录 | true |
| copyResourcesDir | 是否复制resources目录 | true |

## 生成的XML配置说明

生成的Windows服务XML配置文件包含以下重要配置：

1. **日志配置**：默认配置为输出到`%BASE%\..\logs\out`目录，使用旋转日志模式
2. **RunawayProcessKiller扩展**：防止进程失控，包含以下设置：
   - PID文件保存路径：`%BASE%\..\logs\out\<serviceId>.pid`
   - 停止超时时间：10000毫秒
   - 进程停止策略：先停止子进程再停止父进程

## 使用生成的服务

插件执行后，将生成以下目录结构：

1. `<outputDir>/<serviceId>.exe` - WinSW可执行文件
2. `<outputDir>/<serviceId>.xml` - 服务配置文件
3. `${project.build.directory}/<project.build.finalName>.jar` - 您的应用程序JAR
4. `${project.build.directory}/lib` - 复制的lib目录（如果存在）
5. `${project.build.directory}/resources` - 复制的resources目录（如果存在）
6. `<logPath>` - 自动创建的日志目录

要安装服务，请以管理员身份运行命令提示符，然后执行：

```bash
<serviceId>.exe install
```

要启动服务：

```bash
<serviceId>.exe start
```

要停止服务：

```bash
<serviceId>.exe stop
```

要卸载服务：

```bash
<serviceId>.exe uninstall
```

## 注意事项

1. 确保目标Windows机器上已安装适当版本的.NET Framework（对于WinSW.NET4.exe需要.NET Framework 4.0或更高版本）
2. 服务配置文件必须与可执行文件同名，并且位于同一目录
3. 安装和管理服务通常需要管理员权限
4. 使用相对路径时，可以利用%BASE%变量，它将被解析为可执行文件所在目录
   - 例如：`%BASE%\..\logs` 会指向bin目录的父级logs目录
5. 日志目录会在构建时自动创建，即使使用%BASE%变量

## 示例项目

请查看`examples/sample-app`目录以获取完整的示例项目。

### 示例项目使用方法

1. 进入示例项目目录：
   ```bash
   cd examples/sample-app
   ```

2. 编译并打包：
   ```bash
   mvn clean package
   ```

3. 生成Windows服务可执行文件：
   ```bash
   mvn winsw:generate-exe -DprojectName=sample-service -DlogPath="%BASE%\..\logs"
   ```

4. 生成的文件结构：
   - `target/bin/sample-service.exe`
   - `target/bin/sample-service.xml`
   - `target/sample-app-1.0-SNAPSHOT.jar`
   - `target/lib` (从项目复制的依赖库)
   - `target/resources` (从项目复制的资源文件)
   - `target/logs/out` (自动创建的日志目录，包含PID文件)

5. 安装和管理服务：
   ```bash
   cd target/bin
   sample-service.exe install
   sample-service.exe start
   ```

## 许可证

本项目采用MIT许可证 - 详情请参见LICENSE文件