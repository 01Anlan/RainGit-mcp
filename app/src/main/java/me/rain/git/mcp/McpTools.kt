package me.rain.git.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rain.git.auth.SecureStore
import me.rain.git.github.GitHubClient
import me.rain.git.github.RemoteRepo
import me.rain.git.local.LocalGit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** MCP tool adapters around the same LocalGit/GitHubClient used by the app. */
class McpTools(context: Context, private val settings: McpSettings) {
    private val store = SecureStore(context)
    private val git = LocalGit { store.token() }
    private val github = GitHubClient { store.token() }
    private val locks = mutableMapOf<String, Mutex>()
    private val tools = mutableListOf<Tool>()

    private data class Tool(
        val name: String,
        val description: String,
        val schema: String,
        val write: Boolean = false,
        val handler: suspend (JSONObject) -> String
    )

    init {
        add("list_repositories", "List GitHub repositories visible to the signed-in account.", "{}", handler = {
            JSONArray(github.repos().map { repo ->
                mapOf("full_name" to repo.fullName, "private" to repo.privateRepo,
                    "default_branch" to repo.defaultBranch, "description" to repo.description,
                    "updated_at" to repo.updatedAt)
            }).toString()
        })
        add("list_local_repositories", "List repositories in RainGit's working directory.", "{}", handler = {
            JSONArray(localRepos().map { mapOf("name" to it.name, "path" to it.path) }).toString()
        })
        add("get_repository_status", "Get branch, changed files, conflicts and tracking state.",
            repoSchema, handler = { a ->
                val s = git.snapshot(local(a))
                JSONObject(mapOf("repository" to local(a).name, "branch" to s.branch,
                    "staged" to s.staged, "changed" to s.changed, "untracked" to s.untracked,
                    "deleted" to s.deleted, "conflicts" to s.conflicts, "ahead" to s.ahead,
                    "behind" to s.behind, "branches" to s.branches, "tags" to s.tags)).toString()
            })
        add("list_files", "List repository files, excluding .git and dist.", repoSchema, handler = {
            JSONArray(git.files(local(it))).toString()
        })
        add("list_directories", "List repository directories.", repoSchema, handler = {
            JSONArray(git.directories(local(it))).toString()
        })
        add("read_file", "Read a UTF-8 text file (up to 512 KB).", pathSchema, handler = {
            git.read(local(it), it.getString("path"))
        })
        add("search_files", "Search UTF-8 file names and contents. Returns at most 200 matches.",
            schema("repository" to "string", "query" to "string", required = listOf("repository", "query")), handler = { a ->
                val dir = local(a); val query = a.getString("query")
                require(query.isNotBlank()) { "搜索内容不能为空" }
                val matches = mutableListOf<Map<String, Any>>()
                for (path in git.files(dir)) {
                    if (matches.size >= 200) break
                    if (path.contains(query, true)) matches += mapOf("path" to path, "line" to 0, "text" to "文件名匹配")
                    else runCatching { git.read(dir, path) }.getOrNull()?.lineSequence()?.forEachIndexed { index, line ->
                        if (line.contains(query, true) && matches.size < 200)
                            matches += mapOf("path" to path, "line" to index + 1, "text" to line.take(1000))
                    }
                }
                JSONArray(matches).toString()
            })
        add("get_diff", "Get unstaged or staged changes.", schema("repository" to "string", "staged" to "boolean", required = listOf("repository")), handler = {
            git.diff(local(it), it.optBoolean("staged"))
        })
        add("list_commits", "List recent commits (up to 40).", repoSchema, handler = {
            JSONArray(git.snapshot(local(it)).history).toString()
        })
        add("get_commit", "Show a commit and its diff.", schema("repository" to "string", "commit" to "string", required = listOf("repository", "commit")), handler = {
            git.commitDetails(local(it), it.getString("commit"))
        })
        add("clone_repository", "Clone a GitHub repository into RainGit's working directory.",
            schema("full_name" to "string", required = listOf("full_name")), write = true, handler = { a ->
                val r = remote(a.getString("full_name")); git.clone(r, store.root()).path
            })
        add("write_file", "Create or overwrite a UTF-8 text file (up to 512 KB).",
            schema("repository" to "string", "path" to "string", "content" to "string", required = listOf("repository", "path", "content")), write = true, handler = { a ->
                val dir = local(a); val path = a.getString("path")
                val content = a.getString("content")
                locked(dir) {
                    // Validate through LocalGit.within(), even when the target is a new file.
                    if (!File(dir, path).exists()) git.createFile(dir, path)
                    git.write(dir, path, content); "已保存 $path"
                }
            })
        add("create_directory", "Create a directory and a .gitkeep file.", pathSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.createDirectory(dir, it.getString("path")); "文件夹已创建" }
        })
        add("rename_file", "Rename or move a file.", schema("repository" to "string", "path" to "string", "destination" to "string", required = listOf("repository", "path", "destination")), write = true, handler = {
            val dir = local(it); locked(dir) { git.renameFile(dir, it.getString("path"), it.getString("destination")); "文件已重命名" }
        })
        add("delete_file", "Delete a repository file.", pathSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.deleteFile(dir, it.getString("path")); "文件已删除" }
        })
        add("fetch_repository", "Fetch remote changes.", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.fetch(dir) }
        })
        add("pull_repository", "Pull and merge remote changes.", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.pull(dir) }
        })
        add("stage_files", "Stage a path, or all changes when path is '.'.", pathSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.stage(dir, it.getString("path")); "已暂存" }
        })
        add("unstage_files", "Unstage a path, or all paths when path is '.'.", pathSchema, write = true, handler = {
            val dir = local(it); locked(dir) {
                val path = it.getString("path")
                if (path == ".") git.unstageAll(dir) else git.unstage(dir, path); "已取消暂存"
            }
        })
        add("commit_changes", "Commit staged changes using RainGit's configured author.",
            schema("repository" to "string", "message" to "string", required = listOf("repository", "message")), write = true, handler = { a ->
                val dir = local(a); locked(dir) {
                    git.configureAuthor(dir, store.author, store.email)
                    "提交成功 ${git.commit(dir, a.getString("message"), store.author, store.email)}"
                }
            })
        add("push_repository", "Push the current branch to origin.", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.push(dir) }
        })
        add("create_branch", "Create a local branch.", schema("repository" to "string", "branch" to "string", required = listOf("repository", "branch")), write = true, handler = {
            val dir = local(it); locked(dir) { git.createBranch(dir, it.getString("branch")); "分支已创建" }
        })
        add("checkout_branch", "Checkout a clean local or origin branch.", schema("repository" to "string", "branch" to "string", required = listOf("repository", "branch")), write = true, handler = {
            val dir = local(it); locked(dir) { git.checkout(dir, it.getString("branch")); "分支已切换" }
        })
        add("create_stash", "Stash tracked and untracked changes, excluding dist.", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { "Stash ${git.stash(dir)}" }
        })
        add("apply_stash", "Apply the latest stash to a clean working tree.", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.applyStash(dir) }
        })
        add("list_workflows", "List GitHub Actions workflows.", remoteSchema, handler = {
            JSONArray(github.workflows(remote(it.getString("full_name"))).map { w ->
                mapOf("id" to w.id, "name" to w.name, "path" to w.path, "state" to w.state) }).toString()
        })
        add("list_workflow_runs", "List recent GitHub Actions runs.", remoteSchema, handler = {
            JSONArray(github.runs(remote(it.getString("full_name"))).map(::runMap)).toString()
        })
        add("get_workflow_run", "Get an Actions run and its jobs.", runSchema, handler = { a ->
            val r = remote(a.getString("full_name")); val run = github.run(r, a.getLong("run_id"))
            JSONObject(mapOf("run" to runMap(run), "jobs" to github.jobs(r, run).map { j ->
                mapOf("id" to j.id, "name" to j.name, "status" to j.status, "conclusion" to j.conclusion,
                    "steps" to j.steps.map { s -> mapOf("name" to s.name, "status" to s.status, "conclusion" to s.conclusion) })
            })).toString()
        })
        add("get_workflow_logs", "Get logs for a run, or one job when job_id is provided.",
            schema("full_name" to "string", "run_id" to "integer", "job_id" to "integer", required = listOf("full_name", "run_id")), handler = { a ->
                val r = remote(a.getString("full_name")); val cache = File.createTempFile("rain-mcp-log-", ".tmp")
                try {
                    val text = if (a.has("job_id")) github.readableJobLogs(r, a.getLong("job_id"), cache)
                    else github.readableLogs(r, github.run(r, a.getLong("run_id")), cache)
                    if (text.length > 120_000) text.takeLast(120_000) else text
                } finally { cache.delete() }
            })
        add("dispatch_workflow", "Trigger a workflow_dispatch event.",
            schema("full_name" to "string", "workflow_id" to "integer", "branch" to "string", "inputs" to "string", required = listOf("full_name", "workflow_id", "branch")), write = true, handler = { a ->
                val r = remote(a.getString("full_name"))
                val w = github.workflows(r).firstOrNull { it.id == a.getLong("workflow_id") }
                    ?: throw IllegalArgumentException("工作流不存在")
                github.dispatch(r, w, a.getString("branch"), a.optString("inputs")); "工作流已触发"
            })
        add("control_workflow_run", "Cancel, rerun, or rerun-failed-jobs for an Actions run.",
            schema("full_name" to "string", "run_id" to "integer", "operation" to "string", required = listOf("full_name", "run_id", "operation")), write = true, handler = { a ->
                val r = remote(a.getString("full_name"))
                github.control(r, github.run(r, a.getLong("run_id")), a.getString("operation")); "操作已提交"
            })
        add("list_issues", "List open issues, excluding pull requests.", remoteSchema, handler = {
            JSONArray(github.collaboration(remote(it.getString("full_name")), 1).map(::collaborationMap)).toString()
        })
        add("list_pull_requests", "List open pull requests.", remoteSchema, handler = {
            JSONArray(github.collaboration(remote(it.getString("full_name")), 0).map(::collaborationMap)).toString()
        })
        add("create_issue", "Create a GitHub issue.",
            schema("full_name" to "string", "title" to "string", "body" to "string", required = listOf("full_name", "title")), write = true, handler = {
                val n = github.createIssue(remote(it.getString("full_name")), it.getString("title"), it.optString("body")); "Issue #$n 已创建"
            })
    }

    fun definitions() = JSONArray(tools.map {
        JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", JSONObject(it.schema))
    })

    suspend fun call(name: String, args: JSONObject): String {
        val tool = tools.firstOrNull { it.name == name } ?: throw IllegalArgumentException("未知工具：$name")
        if (tool.write) require(settings.allowWrites) { "修改类工具已在 MCP 设置中关闭" }
        return tool.handler(args)
    }

    val writeToolNames get() = tools.filter { it.write }.map { it.name }

    private fun add(name: String, description: String, schema: String, write: Boolean = false,
        handler: suspend (JSONObject) -> String) { tools += Tool(name, description, schema, write, handler) }

    private fun localRepos() = store.root().listFiles()?.filter { git.isRepo(it) }?.sortedBy { it.name }.orEmpty()

    private fun local(a: JSONObject): File {
        val name = a.getString("repository")
        require(Regex("[A-Za-z0-9_.-]+").matches(name)) { "本地仓库名称无效" }
        val dir = File(store.root(), name).canonicalFile
        require(git.isRepo(dir)) { "本地仓库不存在：$name" }
        return dir
    }

    fun localRepositoryExists(name: String): Boolean = runCatching {
        require(Regex("[A-Za-z0-9_.-]+").matches(name))
        git.isRepo(File(store.root(), name).canonicalFile)
    }.getOrDefault(false)

    private fun remote(fullName: String): RemoteRepo {
        val parts = fullName.split('/')
        require(parts.size == 2 && parts.all { Regex("[A-Za-z0-9_.-]+").matches(it) }) { "仓库应写成 owner/name" }
        return RemoteRepo(parts[0], parts[1], "", fullName, false, "main", "")
    }

    private suspend fun <T> locked(dir: File, block: () -> T): T = withContext(Dispatchers.IO) {
        val mutex = synchronized(locks) { locks.getOrPut(dir.canonicalPath) { Mutex() } }
        mutex.withLock { block() }
    }

    private fun runMap(r: me.rain.git.github.Run) = mapOf("id" to r.id, "name" to r.name,
        "number" to r.number, "status" to r.status, "conclusion" to r.conclusion,
        "branch" to r.branch, "sha" to r.sha, "url" to r.url, "created_at" to r.created)

    private fun collaborationMap(i: me.rain.git.github.CollaborationItem) = mapOf("number" to i.number,
        "title" to i.title, "state" to i.state, "author" to i.author, "url" to i.url,
        "updated_at" to i.updated, "comments" to i.comments, "draft" to i.draft)

    companion object {
        private val repoSchema = schema("repository" to "string", required = listOf("repository"))
        private val remoteSchema = schema("full_name" to "string", required = listOf("full_name"))
        private val runSchema = schema("full_name" to "string", "run_id" to "integer", required = listOf("full_name", "run_id"))
        private val pathSchema = schema("repository" to "string", "path" to "string", required = listOf("repository", "path"))

        private fun schema(vararg properties: Pair<String, String>, required: List<String> = emptyList()) =
            JSONObject().put("type", "object").put("properties", JSONObject().apply {
                properties.forEach { (name, type) -> put(name, JSONObject().put("type", type)) }
            }).put("required", JSONArray(required)).put("additionalProperties", false).toString()
    }
}
