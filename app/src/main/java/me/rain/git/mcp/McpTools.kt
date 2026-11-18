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
    private val gateway = me.rain.git.github.GitGateway(context)
    private val git = LocalGit({ store.token() }, gateway)
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
        add("list_repositories", "列出当前 GitHub 账号可见的远程仓库，包括默认分支、私有状态和更新时间。", "{}", handler = {
            JSONArray(github.repos().map { repo ->
                mapOf("full_name" to repo.fullName, "private" to repo.privateRepo,
                    "default_branch" to repo.defaultBranch, "description" to repo.description,
                    "updated_at" to repo.updatedAt)
            }).toString()
        })
        add("list_local_repositories", "列出 RainGit 工作目录内已经克隆或初始化的本地仓库。", "{}", handler = {
            JSONArray(localRepos().map { mapOf("name" to it.name, "path" to it.path) }).toString()
        })
        add("get_repository_status", "查看本地仓库当前分支、暂存和未暂存修改、冲突、远端领先或落后提交数。",
            repoSchema, handler = { a ->
                val s = git.snapshot(local(a))
                JSONObject(mapOf("repository" to local(a).name, "branch" to s.branch,
                    "staged" to s.staged, "changed" to s.changed, "untracked" to s.untracked,
                    "deleted" to s.deleted, "conflicts" to s.conflicts, "ahead" to s.ahead,
                    "behind" to s.behind, "branches" to s.branches, "tags" to s.tags)).toString()
            })
        add("list_files", "列出仓库内的文本文件，自动排除 .git 和构建产物下载目录 dist。", repoSchema, handler = {
            JSONArray(git.files(local(it))).toString()
        })
        add("list_directories", "列出本地仓库的目录结构。", repoSchema, handler = {
            JSONArray(git.directories(local(it))).toString()
        })
        add("read_file", "读取仓库内指定的 UTF-8 文本文件，最大 512 KB。", pathSchema, handler = {
            git.read(local(it), it.getString("path"))
        })
        add("search_files", "按文件名或文本内容搜索仓库，最多返回 200 条匹配结果。",
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
        add("get_diff", "查看工作区或暂存区的代码差异；staged 为 true 时查看已暂存差异。", schema("repository" to "string", "staged" to "boolean", required = listOf("repository")), handler = {
            git.diff(local(it), it.optBoolean("staged"))
        })
        add("list_commits", "查看最近最多 40 条提交记录及提交 SHA。", repoSchema, handler = {
            JSONArray(git.snapshot(local(it)).history).toString()
        })
        add("get_commit", "查看指定提交的完整说明、作者和代码差异。", schema("repository" to "string", "commit" to "string", required = listOf("repository", "commit")), handler = {
            git.commitDetails(local(it), it.getString("commit"))
        })
        add("clone_repository", "将指定的 GitHub 远程仓库克隆到 RainGit 工作目录。",
            schema("full_name" to "string", required = listOf("full_name")), write = true, handler = { a ->
                val r = remote(a.getString("full_name")); git.clone(r, store.root()).path
            })
        add("write_file", "创建或完整覆盖仓库内的 UTF-8 文本文件，最大 512 KB。",
            schema("repository" to "string", "path" to "string", "content" to "string", required = listOf("repository", "path", "content")), write = true, handler = { a ->
                val dir = local(a); val path = a.getString("path")
                val content = a.getString("content")
                locked(dir) {
                    // Validate through LocalGit.within(), even when the target is a new file.
                    if (!File(dir, path).exists()) git.createFile(dir, path)
                    git.write(dir, path, content); "已保存 $path"
                }
            })
        add("create_directory", "在仓库内创建目录，并生成可提交的 .gitkeep 占位文件。", pathSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.createDirectory(dir, it.getString("path")); "文件夹已创建" }
        })
        add("rename_file", "重命名仓库文件，或把它移动到仓库内的另一个目录。", schema("repository" to "string", "path" to "string", "destination" to "string", required = listOf("repository", "path", "destination")), write = true, handler = {
            val dir = local(it); locked(dir) { git.renameFile(dir, it.getString("path"), it.getString("destination")); "文件已重命名" }
        })
        add("delete_file", "删除仓库内的指定文件，删除后仍需提交并推送。", pathSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.deleteFile(dir, it.getString("path")); "文件已删除" }
        })
        add("fetch_repository", "从 origin 获取远程分支和提交，不修改当前工作区。", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.fetch(dir) }
        })
        add("pull_repository", "拉取并合并远程修改；出现冲突时返回冲突信息。", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.pull(dir) }
        })
        add("stage_files", "暂存指定文件；path 填 . 时暂存全部可用修改。", pathSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.stage(dir, it.getString("path")); "已暂存" }
        })
        add("unstage_files", "取消暂存指定文件；path 填 . 时取消全部暂存。", pathSchema, write = true, handler = {
            val dir = local(it); locked(dir) {
                val path = it.getString("path")
                if (path == ".") git.unstageAll(dir) else git.unstage(dir, path); "已取消暂存"
            }
        })
        add("commit_changes", "使用 RainGit 设置中的 Git 作者和邮箱提交已暂存的修改。",
            schema("repository" to "string", "message" to "string", required = listOf("repository", "message")), write = true, handler = { a ->
                val dir = local(a); locked(dir) {
                    git.configureAuthor(dir, store.author, store.email)
                    "提交成功 ${git.commit(dir, a.getString("message"), store.author, store.email)}"
                }
            })
        add("push_repository", "将本地当前分支推送到 GitHub 的 origin 远程仓库。", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.push(dir) }
        })
        add("create_branch", "在本地仓库创建新分支，但不会自动切换。", schema("repository" to "string", "branch" to "string", required = listOf("repository", "branch")), write = true, handler = {
            val dir = local(it); locked(dir) { git.createBranch(dir, it.getString("branch")); "分支已创建" }
        })
        add("checkout_branch", "切换到本地或 origin 远程分支；要求当前工作区没有未提交修改。", schema("repository" to "string", "branch" to "string", required = listOf("repository", "branch")), write = true, handler = {
            val dir = local(it); locked(dir) { git.checkout(dir, it.getString("branch")); "分支已切换" }
        })
        add("create_stash", "把当前已跟踪和未跟踪修改保存到 Stash，dist 目录不受影响。", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { "Stash ${git.stash(dir)}" }
        })
        add("apply_stash", "在干净的工作区恢复最近一次 Stash。", repoSchema, write = true, handler = {
            val dir = local(it); locked(dir) { git.applyStash(dir) }
        })
        add("list_workflows", "列出远程仓库中的 GitHub Actions 工作流及工作流 ID。", remoteSchema, handler = {
            JSONArray(github.workflows(remote(it.getString("full_name"))).map { w ->
                mapOf("id" to w.id, "name" to w.name, "path" to w.path, "state" to w.state) }).toString()
        })
        add("list_workflow_runs", "列出远程仓库最近的 Actions 运行记录和运行 ID。", remoteSchema, handler = {
            JSONArray(github.runs(remote(it.getString("full_name"))).map(::runMap)).toString()
        })
        add("get_workflow_run", "查看指定 Actions 运行的状态、Jobs 和执行步骤。", runSchema, handler = { a ->
            val r = remote(a.getString("full_name")); val run = github.run(r, a.getLong("run_id"))
            JSONObject(mapOf("run" to runMap(run), "jobs" to github.jobs(r, run).map { j ->
                mapOf("id" to j.id, "name" to j.name, "status" to j.status, "conclusion" to j.conclusion,
                    "steps" to j.steps.map { s -> mapOf("name" to s.name, "status" to s.status, "conclusion" to s.conclusion) })
            })).toString()
        })
        add("get_workflow_logs", "下载并读取构建日志；提供 job_id 时仅读取指定任务，否则读取整次运行日志。",
            schema("full_name" to "string", "run_id" to "integer", "job_id" to "integer", required = listOf("full_name", "run_id")), handler = { a ->
                val r = remote(a.getString("full_name")); val cache = File.createTempFile("rain-mcp-log-", ".tmp")
                try {
                    val text = if (a.has("job_id")) github.readableJobLogs(r, a.getLong("job_id"), cache)
                    else github.readableLogs(r, github.run(r, a.getLong("run_id")), cache)
                    if (text.length > 120_000) text.takeLast(120_000) else text
                } finally { cache.delete() }
            })
        add("dispatch_workflow", "手动触发支持 workflow_dispatch 的 GitHub Actions 工作流。",
            schema("full_name" to "string", "workflow_id" to "integer", "branch" to "string", "inputs" to "string", required = listOf("full_name", "workflow_id", "branch")), write = true, handler = { a ->
                val r = remote(a.getString("full_name"))
                val w = github.workflows(r).firstOrNull { it.id == a.getLong("workflow_id") }
                    ?: throw IllegalArgumentException("工作流不存在")
                github.dispatch(r, w, a.getString("branch"), a.optString("inputs")); "工作流已触发"
            })
        add("control_workflow_run", "控制 Actions 运行；operation 支持 cancel、rerun、rerun-failed-jobs。",
            schema("full_name" to "string", "run_id" to "integer", "operation" to "string", required = listOf("full_name", "run_id", "operation")), write = true, handler = { a ->
                val r = remote(a.getString("full_name"))
                github.control(r, github.run(r, a.getLong("run_id")), a.getString("operation")); "操作已提交"
            })
        add("list_issues", "列出远程仓库尚未关闭的 Issue，不包含 Pull Request。", remoteSchema, handler = {
            JSONArray(github.collaboration(remote(it.getString("full_name")), 1).map(::collaborationMap)).toString()
        })
        add("list_pull_requests", "列出远程仓库尚未关闭的 Pull Request。", remoteSchema, handler = {
            JSONArray(github.collaboration(remote(it.getString("full_name")), 0).map(::collaborationMap)).toString()
        })
        add("create_issue", "在指定的 GitHub 仓库创建 Issue，可填写标题和正文。",
            schema("full_name" to "string", "title" to "string", "body" to "string", required = listOf("full_name", "title")), write = true, handler = {
                val n = github.createIssue(remote(it.getString("full_name")), it.getString("title"), it.optString("body")); "Issue #$n 已创建"
            })
        add("create_comment", "在指定 Issue 或 Pull Request 下发表普通评论。",
            schema("full_name" to "string", "number" to "integer", "body" to "string", required = listOf("full_name", "number", "body")), write = true, handler = {
                github.createComment(remote(it.getString("full_name")), it.getInt("number"), it.getString("body"))
            })
        add("list_artifacts", "列出指定 Actions 运行生成的构建产物，包括产物 ID、大小和过期状态。", runSchema, handler = { a ->
            val r = remote(a.getString("full_name"))
            JSONArray(github.artifacts(r, github.run(r, a.getLong("run_id"))).map {
                mapOf("id" to it.id, "name" to it.name, "size" to it.size, "expired" to it.expired) }).toString()
        })
        add("download_artifact", "下载指定构建产物到本地仓库的 dist 目录；format 支持 zip 或 apk。",
            schema("full_name" to "string", "run_id" to "integer", "artifact_id" to "integer", "format" to "string", required = listOf("full_name", "run_id", "artifact_id")), write = true, handler = { a ->
                val r = remote(a.getString("full_name")); val run = github.run(r, a.getLong("run_id"))
                val artifact = github.artifacts(r, run).firstOrNull { it.id == a.getLong("artifact_id") }
                    ?: throw IllegalArgumentException("构建产物不存在")
                require(!artifact.expired) { "构建产物已过期" }
                val dir = git.directory(store.root(), r); val dist = git.downloadDirectory(dir)
                val archive = File.createTempFile(".rain-mcp-artifact-", ".zip", dist)
                var saved: File? = null
                try {
                    github.download(r, "/actions/artifacts/${artifact.id}/zip", archive)
                    val apkOnly = a.optString("format", "zip").equals("apk", true)
                    require(apkOnly || a.optString("format", "zip").equals("zip", true)) { "format 仅支持 zip 或 apk" }
                    val name = artifact.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "artifact" }
                    saved = File(dist, "$name-${artifact.id}.${if (apkOnly) "apk" else "zip"}")
                    var suffix = 1
                    while (saved!!.exists()) saved = File(dist, "$name-${artifact.id}-${suffix++}.${if (apkOnly) "apk" else "zip"}")
                    if (!apkOnly) require(archive.renameTo(saved)) { "无法保存 ZIP 构建产物" }
                    else extractApk(archive, saved!!)
                    saved!!.absolutePath
                } catch (e: Exception) { saved?.delete(); throw e }
                finally { archive.delete() }
            })
        add("get_pull_request", "查看指定 Pull Request 的分支、提交、合并状态和正文。",
            schema("full_name" to "string", "number" to "integer", required = listOf("full_name", "number")), handler = {
                val j = github.pullRequest(remote(it.getString("full_name")), it.getInt("number"))
                JSONObject(mapOf("number" to j.getInt("number"), "title" to j.getString("title"),
                    "state" to j.getString("state"), "body" to j.optString("body"), "draft" to j.optBoolean("draft"),
                    "mergeable" to j.opt("mergeable"), "head" to j.getJSONObject("head").getString("ref"),
                    "base" to j.getJSONObject("base").getString("ref"), "head_sha" to j.getJSONObject("head").getString("sha"),
                    "url" to j.getString("html_url"))).toString()
            })
        add("create_pull_request", "创建 Pull Request；head 是来源分支，base 是目标分支，draft 控制是否创建草稿。",
            schema("full_name" to "string", "title" to "string", "head" to "string", "base" to "string", "body" to "string", "draft" to "boolean", required = listOf("full_name", "title", "head", "base")), write = true, handler = { a ->
                val j = github.createPullRequest(remote(a.getString("full_name")), a.getString("title"),
                    a.getString("head"), a.getString("base"), a.optString("body"), a.optBoolean("draft"))
                JSONObject(mapOf("number" to j.getInt("number"), "url" to j.getString("html_url"))).toString()
            })
        add("merge_pull_request", "合并 Pull Request；method 支持 merge、squash、rebase，message 可自定义提交标题。",
            schema("full_name" to "string", "number" to "integer", "message" to "string", "method" to "string", required = listOf("full_name", "number")), write = true, handler = {
                github.mergePullRequest(remote(it.getString("full_name")), it.getInt("number"),
                    it.optString("message"), it.optString("method", "merge")).toString()
            })
        add("list_reviews", "列出指定 Pull Request 的评审结果、评审人和正文。",
            schema("full_name" to "string", "number" to "integer", required = listOf("full_name", "number")), handler = { a ->
                val items = github.reviews(remote(a.getString("full_name")), a.getInt("number"))
                JSONArray((0 until items.length()).map { i -> items.getJSONObject(i).let {
                    mapOf("id" to it.getLong("id"), "author" to it.getJSONObject("user").getString("login"),
                        "state" to it.getString("state"), "body" to it.optString("body"), "submitted_at" to it.optString("submitted_at")) } }).toString()
            })
        add("create_review", "提交 Pull Request 评审；event 支持 APPROVE、REQUEST_CHANGES、COMMENT。",
            schema("full_name" to "string", "number" to "integer", "body" to "string", "event" to "string", required = listOf("full_name", "number", "event")), write = true, handler = { a ->
                val j = github.createReview(remote(a.getString("full_name")), a.getInt("number"), a.optString("body"), a.getString("event"))
                JSONObject(mapOf("id" to j.getLong("id"), "state" to j.getString("state"))).toString()
            })
        add("list_review_comments", "列出 Pull Request 中针对具体代码行和文件的评审评论。",
            schema("full_name" to "string", "number" to "integer", required = listOf("full_name", "number")), handler = { a ->
                val items = github.reviewComments(remote(a.getString("full_name")), a.getInt("number"))
                JSONArray((0 until items.length()).map { i -> items.getJSONObject(i).let {
                    mapOf("id" to it.getLong("id"), "path" to it.getString("path"), "line" to it.optInt("line"),
                        "side" to it.optString("side"), "body" to it.getString("body"),
                        "author" to it.getJSONObject("user").getString("login"), "url" to it.getString("html_url")) } }).toString()
            })
        add("create_review_comment", "对 Pull Request 的指定代码行发表评论；需要最新 head_sha、文件路径、行号和 LEFT/RIGHT 方向。",
            schema("full_name" to "string", "number" to "integer", "body" to "string", "head_sha" to "string", "path" to "string", "line" to "integer", "side" to "string", required = listOf("full_name", "number", "body", "head_sha", "path", "line")), write = true, handler = { a ->
                val j = github.createReviewComment(remote(a.getString("full_name")), a.getInt("number"), a.getString("body"),
                    a.getString("head_sha"), a.getString("path"), a.getInt("line"), a.optString("side", "RIGHT"))
                JSONObject(mapOf("id" to j.getLong("id"), "url" to j.getString("html_url"))).toString()
            })
        add("create_remote_branch", "在 GitHub 远程仓库创建分支；sha 必须是目标提交的完整 40 位 SHA。",
            schema("full_name" to "string", "branch" to "string", "sha" to "string", required = listOf("full_name", "branch", "sha")), write = true, handler = {
                github.createBranch(remote(it.getString("full_name")), it.getString("branch"), it.getString("sha")).toString()
            })
        add("create_remote_tag", "在 GitHub 远程仓库创建轻量标签；sha 必须是目标提交的完整 40 位 SHA。",
            schema("full_name" to "string", "tag" to "string", "sha" to "string", required = listOf("full_name", "tag", "sha")), write = true, handler = {
                github.createTag(remote(it.getString("full_name")), it.getString("tag"), it.getString("sha")).toString()
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

    private fun extractApk(archive: File, target: File) {
        java.util.zip.ZipFile(archive).use { zip ->
            val apks = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.toList()
            require(apks.size == 1) { if (apks.isEmpty()) "产物中没有 APK" else "产物包含多个 APK，请下载 ZIP" }
            val entry = apks.single()
            require(entry.size in 1..(1024L * 1024 * 1024)) { "APK 大小不合法" }
            val crc = java.util.zip.CRC32(); var count = 0L
            target.outputStream().buffered().use { output ->
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        count += n; require(count <= 1024L * 1024 * 1024) { "APK 超过大小限制" }
                        crc.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                }
            }
            require(count == entry.size && crc.value == entry.crc) { "APK 校验失败" }
            java.util.zip.ZipFile(target).use { apk -> require(apk.getEntry("AndroidManifest.xml") != null) { "产物不是有效 APK" } }
        }
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
        private val parameterDescriptions by lazy { mapOf(
            "repository" to "本地仓库目录名，可通过 list_local_repositories 查询",
            "full_name" to "GitHub 仓库全名，格式为 所有者/仓库名",
            "path" to "仓库内的相对文件路径；暂存或取消暂存全部文件时填 .",
            "content" to "要写入文件的完整 UTF-8 文本内容",
            "query" to "要搜索的文件名或文本内容",
            "staged" to "是否查看暂存区差异，默认为 false",
            "commit" to "提交 SHA 或可解析的提交引用",
            "destination" to "文件重命名或移动后的仓库内相对路径",
            "message" to "提交说明、合并提交标题或评论正文，视工具用途而定",
            "branch" to "分支名称，例如 main 或 origin/main",
            "workflow_id" to "GitHub Actions 工作流 ID",
            "inputs" to "工作流输入参数，JSON 对象字符串，可留空",
            "run_id" to "GitHub Actions 运行 ID",
            "job_id" to "Actions 任务 ID，可选；提供后仅获取该任务日志",
            "operation" to "运行控制操作：cancel、rerun 或 rerun-failed-jobs",
            "title" to "Issue 或 Pull Request 的标题",
            "body" to "正文、评论或评审意见",
            "number" to "Issue 或 Pull Request 的编号",
            "artifact_id" to "构建产物 ID，可通过 list_artifacts 查询",
            "format" to "下载格式：zip 保留完整产物，apk 提取其中唯一的 APK",
            "head" to "Pull Request 来源分支",
            "base" to "Pull Request 目标分支",
            "draft" to "是否创建草稿 Pull Request，默认为 false",
            "method" to "合并方式：merge、squash 或 rebase，默认为 merge",
            "event" to "评审类型：APPROVE、REQUEST_CHANGES 或 COMMENT",
            "head_sha" to "Pull Request 最新来源提交的完整 40 位 SHA",
            "line" to "要评论的代码行号",
            "side" to "代码方向：RIGHT 表示新代码，LEFT 表示旧代码，默认 RIGHT",
            "sha" to "目标提交的完整 40 位 SHA",
            "tag" to "要创建的远程标签名称"
        ) }

        private val repoSchema = schema("repository" to "string", required = listOf("repository"))
        private val remoteSchema = schema("full_name" to "string", required = listOf("full_name"))
        private val runSchema = schema("full_name" to "string", "run_id" to "integer", required = listOf("full_name", "run_id"))
        private val pathSchema = schema("repository" to "string", "path" to "string", required = listOf("repository", "path"))

        private fun schema(vararg properties: Pair<String, String>, required: List<String> = emptyList()) =
            JSONObject().put("type", "object").put("properties", JSONObject().apply {
                properties.forEach { (name, type) -> put(name, JSONObject().put("type", type).put("description", parameterDescriptions[name].orEmpty())) }
            }).put("required", JSONArray(required)).put("additionalProperties", false).toString()

    }
}
