# RainGit MCP

RainGit 内置一个 Streamable HTTP MCP Server。登录 GitHub 后，在底部 **MCP** 页面设置端口并启动。

## 连接

- MCP 地址：`http://127.0.0.1:8765/mcp`（端口以页面为准）
- 健康检查：`GET http://127.0.0.1:8765/health`
- 协议：MCP `2025-03-26`，JSON-RPC 2.0，单个 POST 请求对应单个 JSON 响应
- 支持：`initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`
- 监听地址固定为 `127.0.0.1`，同一设备上的 MCP 客户端可直接连接。

通用客户端配置示例：

```json
{
  "mcpServers": {
    "raingit": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

## 参数约定

- 本地工具使用 `repository`：RainGit 工作目录下的文件夹名，例如 `owner--repository`。
- GitHub 工具使用 `full_name`：完整仓库名，例如 `octocat/Hello-World`。
- 文件路径都是仓库内的相对路径；沿用 App 的 `.git`、`dist`、符号链接和文件大小保护。
- “允许修改与推送”关闭后，写入、提交、推送、触发 Actions 等工具会返回工具错误。

## 工具

查询：`list_repositories`、`list_local_repositories`、`get_repository_status`、`list_files`、`list_directories`、`read_file`、`search_files`、`get_diff`、`list_commits`、`get_commit`、`list_workflows`、`list_workflow_runs`、`get_workflow_run`、`get_workflow_logs`、`list_issues`、`list_pull_requests`。

修改：`clone_repository`、`write_file`、`create_directory`、`rename_file`、`delete_file`、`fetch_repository`、`pull_repository`、`stage_files`、`unstage_files`、`commit_changes`、`push_repository`、`create_branch`、`checkout_branch`、`create_stash`、`apply_stash`、`dispatch_workflow`、`control_workflow_run`、`create_issue`。

## 后台与悬浮窗

- MCP 由用户主动启动的 Android 前台服务运行，退出页面不会停止服务。
- 通知栏提供服务状态、打开 App 和关闭服务操作。
- “后台无限制运行”入口用于设置系统电池优化白名单。
- 悬浮窗可拖动，点击主体打开 App，按钮直接启停 MCP；悬浮窗权限不是 MCP 启动的前置条件。
