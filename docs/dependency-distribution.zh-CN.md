# JEI / Jade 分发核查与发布规则

核查日期：2026-10-23。范围为当前 `26.2-fabric` 工作区、全部本地可达 Git 历史，以及
`054545641/ForestryCE-Refabricated`（仓库现名）的 GitHub Releases、Actions artifacts 和缓存列表。

## 许可核实

| 依赖 | 当前固定版本 | 官方许可与来源 |
| --- | --- | --- |
| JEI | `30.42.0.236`，Fabric 26.2 | [MIT](https://github.com/mezz/JustEnoughItems/blob/26.2/LICENSE.txt)；再分发副本或实质内容须保留版权及许可声明 |
| Jade | Modrinth `ue8CO97w`，`26.2.11+fabric` | [精确版本页](https://modrinth.com/mod/jade/version/ue8CO97w)标记 CC BY-NC-SA 4.0；须遵守署名、非商业等条件，分享改编作品另有相同方式共享要求 |

这两款模组都不是一概禁止再分发。风险在于复制或上传其内容时没有满足对应许可条件。
[Jade 官方许可页](https://www.curseforge.com/minecraft/mc-mods/jade/license)区分旧版本 MIT 和 1.16 起的 CC BY-NC-SA 4.0；
未找到当前版本 Jade API 单独采用 MIT 的官方例外。

本次另读取本机相邻开发项目中已缓存的精确版本 JAR，核对 `fabric.mod.json`、Manifest 和随包许可：

| 文件 | SHA-256 |
| --- | --- |
| `jei-26.2-fabric-30.42.0.236.jar` | `8510fdb58d2eeffb2ff61ba5659ed9d52139426dea9594822032cd4eb997c3d9` |
| `jade-ue8CO97w.jar` | `ffe5d2e83a531c3fbe75ad54095e6a9eafc73a38d5827b4c90bedb4b03618b87` |

## 核查结果与修正

- 当前 Git 跟踪文件及全部本地可达历史（1 个提交，非浅克隆）未发现 JEI/Jade JAR。
  按文件头扫描了全部 18,964 个跟踪文件，唯一 ZIP/JAR 格式文件为 Gradle Wrapper，未发现独立 CLASS 文件；
  `.local/`、`build/`、`run/`、`libs/` 已忽略。
- `build.gradle` 使用 `compileOnly` 编译兼容代码，`runtimeOnly` 提供开发环境中的可选运行依赖，
  没有将这些依赖加入发行 JAR。`withJade=false`、`withJei=false` 只关闭开发运行时加载，编译仍需下载 API 依赖。
- GitHub 公开 API 核查时 Releases 为 0、Actions artifacts 为 0，但已有 16 个 Gradle 缓存，
  包括 2 个 `gradle-dependencies-v2` 缓存。未解包确认这些缓存包含哪些模组，不将其直接定性为侵权。
  已清理这 16 个可重新生成的缓存，随后 API 复核缓存数为 0。
- [Gradle Action 官方说明](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md#caching-build-state-between-jobs)
  确认默认缓存会保存下载的依赖。两个工作流的全部 `setup-gradle` 步骤已设置 `cache-disabled: true`，
  以避免再次将第三方依赖缓存上传 GitHub。
- 原构建工作流在检查失败后仍会上传整个 `libs/`。现改为仅在构建成功后上传
  `stageFabricDistribution` 整理的四个已校验 Forestry JAR；失败时仅上传诊断报告。
- `checkFabricF3` 保留自有源码清单检查，并明确拒绝 JEI/Jade 类与资源目录、嵌套 JAR/ZIP、
  禁用的 JAR 文件和 `fabric.mod.json` 的嵌套依赖声明。
- `.gitignore` 增加通用 JAR、ZIP、CLASS 等规则，避免依赖被放到其他目录时误提交，保留 Wrapper 例外。

核查与清理的原始 API 记录保留在本机忽略目录 `.local/distribution-audit/`。
这些结果是本次核查时的快照，不能证明历史上从未有过已删除或过期的产物。
工作流防护随此文档一同版本控制，仅对已同步改动的分支生效。

## GitHub Actions 权限复核

- 远端默认 `GITHUB_TOKEN` 权限为 `read`，禁止 Actions 创建或审批 PR；所有外部贡献者的 fork PR 运行都需审批。
  这些限制保持不变，两个工作流还明确声明 `contents: read`，checkout 不保留 Git 凭据。
- 原远端策略为 `local_only`，会拒绝工作流所需的外部官方 Action。经仓库所有者明确批准，
  改为 `selected`，仅允许 checkout、setup-java、setup-gradle、upload-artifact 的四个已核实提交；
  `github_owned_allowed`、`verified_allowed` 均为 `false`，`sha_pinning_required` 保持 `true`。
  完整 SHA 固定在工作流中，升级 Action 时也需同步审核远端白名单。
- 两个工作流均设置顶层 `cache-mode: none`，同时保留 Gradle Action 的 `cache-disabled: true`。
  实际生效状态通过 job 初始化日志中的 `Cache mode: none` 和运行后的远端缓存列表复核；
  不依赖普通 shell 是否暴露 `ACTIONS_CACHE_MODE` 环境变量。
  [GitHub 官方说明](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#cache-mode)
  确认该模式由缓存服务按令牌权限限制读写，不仅是构建工具的缓存偏好。
- 构建使用普通 `pull_request` 事件、GitHub 托管 runner，不向 PR 构建提供发布密钥；
  未使用 `pull_request_target` 或 `workflow_run` 执行贡献者代码。
  旧版发布任务明确限定 `push` 到 `1.21.1` 且提交说明以 `[Release]` 开头，未扩大原发布范围。
- 默认分支当前没有分支保护或 ruleset。有仓库写权限的人员仍可直接修改工作流、跳过 PR 审查；
  上述防护不能约束管理员主动修改安全策略。这是剩余的权限管理风险，本次未改变仓库的直接提交流程。
- 远端没有设置 `MINECRAFT_EULA_ACCEPTED`，普通专用服务器 job 按原条件跳过，不代替用户接受 EULA。

远端配置证据保留于 `.local/distribution-audit/github-actions-policy-update.json` 和
`github-actions-allowlist-final.json`；Gradle 白名单包含 `setup-gradle` 子路径。
CI 验证应核对目标提交的全部 job、缓存模式日志及运行结束后的缓存列表；仅看工作流文件不足以确认实际执行结果。

## 本次验证

- 真实工程离线执行 `stageFabricDistribution -PwithJade=false -PwithJei=false` 成功，
  四个 JAR 通过 `checkFabricF3`，证据见 `build/fabric-26.2/reports/f3/isolation.json`。
- 使用实际 Gradle 校验脚本的隔离样例，验证正常四包可整理，误混入 JEI 资源、Jade 资源、
  大写扩展名的嵌套 `.JAR`、`metadata.jars` 四种情况均使校验失败，分发整理不执行。
- 四个源 JAR 不变时，往分发目录加入 `jade.jar`，再次整理仍会执行并移除该文件。
  因此整理任务显式禁用增量跳过，避免 Gradle 忽略输出目录新添文件。
- 工作流结构、忽略规则和 `git diff --check` 通过；本次未启动游戏或运行完整 GameTest。
- 旧 CI 的六项 BOM `.module` 校验失败属于缺少登记。已核对 Maven Central 的原始 JSON、
  坐标、官方 SHA-1 和原先信任的关联 POM，补充六项 SHA-256；保留严格依赖验证。
- CI 还发现本地生成的 `minecraft-merged-e6ef981b3c:26.2` 整包哈希随 ZIP 元数据变化。
  比较 Windows 已信任产物与独立 Linux CI 报告后，30,451 个文件名及内容哈希完全相同，
  内容摘要均为 `bcb4befd56e338259c4e13945ff8baa733f92e13eac1b74ade3826799c5a939d`。
  现仅对这一精确本地生成产物排序、固定时间、去掉目录条目并使用 STORED 封装，再执行固定整包 SHA-256 校验；
  没有信任整个 Minecraft 分组或关闭验证。诊断脚本只报告哈希，不上传 Minecraft JAR。
  独立 Gradle 样例验证了跨时区及不同 ZIP 元数据的输出一致性、重复执行幂等性，
  并确认内容篡改仍被严格校验拒绝，重复文件名和异常路径也会失败。

## 后续发布

### Team Reborn Energy 内嵌例外（2026-10-24）

为使 Forestry 与仅提供能量兼容代码的模组在没有 Tech Reborn 时也可互通，核心包通过 Loom `include`
内嵌官方 `teamreborn:energy:5.0.0`，路径为 `META-INF/jars/energy-5.0.0.jar`；三个扩展包不重复内嵌。
该 API 的[官方许可](https://github.com/TechReborn/Energy/blob/master/LICENSE)为 MIT。
已核对精确版本 JAR 内的 `LICENSE`，保留完整原包中的版权及许可声明，不解包合并、修改或重定位 API 类。
原包 SHA-256 为 `889afc438d3e4add5cfdac76517da7987a2c495e4731690a56f2c5dee775db59`，继续由 Gradle 严格依赖校验。

`team_reborn_energy` 保留原 mod ID，核心要求 `^5.0.0`，由 Fabric Loader 统一选择满足各模组要求的 5.x 版本。
这允许与其他模组内嵌的兼容 API 共存，不增加 Tech Reborn / RebornCore 依赖，也不要求玩家另装 Energy API。
能量交互固定为 1 FE = 1 E，遵循[上游推送与事务约定](https://github.com/TechReborn/Energy#conventions)。

此项替代上文原先对所有嵌套 JAR 的一概拒绝：`checkFabricF3` 仅放行核心元数据中这一条嵌套声明，
并逐字节摘要核对所嵌入的原包（包含许可），其他嵌套包、扩展包内嵌、JEI/Jade 内容仍然拒绝。
正式实例测试不再另放独立 Energy JAR，以免掩盖缺失的嵌套 API。四包发布数量不变。

本次 `stageFabricDistribution` 与全模块的 103 项服务端 GameTest 通过。真实 Fabric Loader 的客户端/服务端
共 20 项解析检查通过，包含本机 BCRefabricated `26.9.17+mc26.2`、Tech Reborn `6.1.1` 单独及共同安装；
这轮检查验证依赖共存，未启动这两款模组的游戏入口点。能量收发通过官方 `SimpleEnergyStorage` 与真实 Forestry
机器/引擎的 GameTest 验证。另用实际 Gradle 校验脚本完成 7 项隔离检查，确认原包放行，额外嵌套、
API 篡改/遗漏、缺失声明、扩展重复内嵌及 Jade 资源混入均被拒绝。

随后完成 BC 单装、TR 单装、BC+TR 三组原生专服联调：实际八条双向传输线路全部通过，
BC 每方向完整传输 4000 E，TR 每方向完整传输 4096 E，源及管线最终清零。
没有放置独立 Energy JAR，三组在游戏中均只加载一个 API 模组和一份 API 类资源；
详见[开发指南的实机联调记录](fabric-development.zh-CN.md)及 `scripts/test-energy-interop.py`。

### 发布命令

```powershell
.\gradlew-fabric.bat stageFabricDistribution -PwithJade=false -PwithJei=false
```

Linux/macOS 使用 `bash gradlew` 执行同一任务。仅在命令成功后取
`build/fabric-26.2/distribution/` 中的四个 JAR 发布，完整 CI 仍执行 `build gametestClasses`。
此任务按四个 JAR 任务的实际输出取文件，不扫描或复制依赖目录；`Sync` 会移除分发目录中的旧文件。

玩家从 [JEI 官方项目](https://modrinth.com/mod/jei)和 [Jade 官方项目](https://modrinth.com/mod/jade)
另行获取可选模组。开发所用 Maven 依赖声明、坐标、哈希和自有兼容代码可以保留；
除上述核实并由构建内嵌的 Energy API 外，不要把开发实例、Gradle 缓存或第三方模组包混入仓库、Actions artifacts 或发行压缩包。
如以后确实需要再分发第三方内容，应针对那次具体版本和用途重新核对许可、附带声明并满足相应条件。
