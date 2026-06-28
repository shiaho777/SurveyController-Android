# SurveyController Android 重构计划

用纯 Kotlin + Jetpack Compose 在 Android 上 1:1 复刻 SurveyController 桌面端功能，
并按手机用户体验重新设计 UI/UX。延续原项目"业务逻辑与 UI 完全解耦"的设计理念。

## 技术栈
- Kotlin 2.x + Jetpack Compose + Material 3 (Material You)
- Kotlin Coroutines + Flow（对应原 asyncio 引擎）
- OkHttp（HTTP 精确控制）+ Jsoup（HTML 解析）
- kotlinx.serialization（JSON / 配置编解码）
- Hilt（依赖注入）
- DataStore（设置/配置持久化）
- CameraX + ML Kit Barcode（扫码 / 二维码图片解析）

## 包结构
```
com.surveycontroller.android
  core/
    model/        题型、配置、答案动作等领域模型
    questions/    答案生成算法（分布/比例/倾向/信度/时长）
    engine/       协程并发执行引擎
    network/      HTTP 客户端、代理
  provider/
    wjx/          问卷星
    tencent/      腾讯问卷
    credamo/      Credamo 见数
    SurveyProvider.kt + Registry
  data/           设置存储、配置编解码、仓库
  ui/             Compose UI（theme / workbench / config / run / settings / components）
```

## 进度跟踪
- [x] 阶段0：架构与骨架（Gradle 工程、Manifest、Application、主题、真实图标与素材）
- [x] 阶段1：领域模型 + 问卷星(wjx)协议层（解析/提交/jqsign/submitdata，12项单测验证）
- [x] 阶段2：腾讯问卷协议层（session/questions API + 答案 JSON 编码 + 提交）
- [x] 阶段3：Credamo 见数协议层（detail/init/save + 双重 SHA1 签名，与 Python 交叉验证）
- [x] 阶段4：答案生成算法（权重抽样/严格比例/倾向/一致性窗口/分布矫正/作答时长）
- [x] 阶段5：运行引擎（协程并发槽/份数认领/暂停停止恢复/失败阈值/提交间隔）
- [x] 阶段6：AI 填空（OpenAI 兼容接口，已接通）
- [x] 阶段5+：代理 IP 池（自定义 API，支持 {num}/{area} 模板与地区编码）
- [x] 阶段6+：人设生成（逻辑自洽画像 + 关键词选项加权 x3）
- [x] 阶段6+：反向填充（轻量 XLSX 读取器 + 列匹配 + 按序号/文本回放 + 运行时预约/回收）
- [x] 阶段4+：心理测量联合优化（Cronbach α 拟合 + 方向推断 + 反向题 + 每份样本锁定选择）
- [x] 前台服务：运行时自动拉起、结束自动停止
- [x] 阶段7：UI/UX（工作台/配置向导/运行监控/设置/关于/扫码 + 维度分组/代理/反向填充入口）

## 验证状态
- `./gradlew assembleDebug` ✅ 生成 app-debug.apk（42MB）
- `./gradlew testDebugUnitTest` ✅ 19 项单测通过
  - wjx 编码 12 + credamo 签名 2 + 心理测量 3 + 反向填充 2
- 协议正确性：wjx submitdata/jqsign、credamo signature 与 Python 端逐字节一致
- 算法正确性：Cronbach α、目标配比、联合锁定、反向填充回放均有单测覆盖

## 1:1 行为对齐（已完成波次）
- [x] 跳题/显隐剪枝 `HttpLogicPlanner`（1:1 build_http_logic_plan：分组显隐、无条件/条件跳转、终止、回退检测）
- [x] 条件规则引擎 `ConsistencyEngine` + `AnsweredTracker`（1:1 consistency.py：单选/矩阵/多选必选禁选，越后越优先）
- [x] 完整填空 `TextValues`（条目类型/multi_text 分隔/姓名手机身份证整数模式/区间）
- [x] 三平台统一走 `buildPlan`（逻辑剪枝 + 条件规则 + 已答记录 + 人设/信度/反向填充）
- [x] wjx 解析器补全跳题(jumpto/hasjump)、显隐(relation)、控制显示目标回填
- 验证：23 项单测全绿（含逻辑剪枝/条件规则 4 项新增）

## 仍与桌面端有差距（后续波次）
- 配置导入/导出编解码（codec）
- 免费 AI 模式（依赖项目私有后端，无法复刻；自定义 OpenAI 已可用）
- 提交报告统计维度
- 腾讯/见数的跳题显隐逻辑元数据解析（目前 wjx 完整，腾讯/见数仅基础）
- UI 配置面：选项内嵌填空、多空模式、分布模式(自定义比例/随机)、强制项编辑、条件规则编辑器、维度分组管理




## 复用的原项目素材
- 启动图标：assets/icon.png（2048²）→ mipmap 各密度 ic_launcher(_round)
- 社区二维码：assets/community_qr.png → res/drawable-nodpi/community_qr.png
- 赞赏码：WeDonate.png → donate_wechat.png；AliDonate.jpg → donate_alipay.jpg
- 应用 Logo：icon.png → drawable-nodpi/app_logo.png（关于页/启动页用）
- IP 地区数据：software/assets/{area.txt, area_codes_2022.json, location_tree_2022.json} → app assets
- 反向填充示例：reverse_fill_example.xlsx → app assets

## 协议复刻关键备忘（问卷星 wjx，已核对源码）
- 解析：GET 问卷链接 → Jsoup 解析 `div#divQuestion` → 每个 fieldset 为一页 → `div[topic]` 为题。
- type_code → entry_type 映射见 core/model/QuestionType.kt。
- 提交：POST `https://{domain}/joinnew/processjq.ashx`，domain = host 含 ks.wjx.com ? ks.wjx.com : v.wjx.cn。
- submitdata 编码：题间 `}`，题内 `{num}${answer}`；选项 index+1，多选 `|`，选项填空 `!text`，
  矩阵行 `{row+1}!{col+1}` 以 `,` 连接，多空填空 `^`，排序 `,`。提交前中文逗号→英文逗号。
- jqsign = jqnonce 每字符 XOR t，t = ktimes%10（为0则取1）。
- cst = start_seconds*1000；starttime 格式 `yyyy/M/d H:m:s`（本地、无前导零）；start = now - ktimes。
- 成功/验证/错误响应分类见 provider/wjx。

## 私有后端客户端对接（已完成波次）
- [x] 设备标识 DeviceIdentity（sc-v2-+32hex，DataStore 持久化）
- [x] 随机IP会话 RandomIpSessionStore（user_id + 额度持久化）
- [x] BackendClient 全端点 1:1：
  - /api/auth/trial（领取试用/同步额度）、/api/bonus（彩蛋）、/api/cards/redeem（卡密兑换）
  - /api/ip/extract（默认/福利源代理提取，含 minute/pool/upstream/area/num）
  - /api/ai/free（免费 AI 填空，身份缺失自动领取试用）
  - /api/status（服务状态）、/api/submission/report（提交结果上报）
  - 统一 X-Device-ID 头，错误 detail 透传
- [x] BackendProxyPool（默认/福利源）+ ProxyPool（自定义 API），引擎按 proxySource 选择
- [x] FreeAiTextProvider（免费 AI）与 OpenAiTextProvider（自定义）按 AI 模式切换
- [x] 提交成功/失败后自动上报后端
- [x] UI：设置页"随机IP账号"面板（领取试用/同步额度/卡密兑换/服务状态）+ AI 模式切换；配置页代理源选择
- 验证：25 项单测全绿（新增后端端点契约 2 项）

## 全量对齐波次（已完成）
- [x] 见数强制项/算术题/多选限制解析 CredamoQuestionRules（1:1 parser.py）
- [x] 腾讯跳题/显隐逻辑 TencentLogic（goto/display/refer → jump/display/controls）
- [x] 配置导入/导出 ConfigCodec（schema v6 字段名，含题目结构+各题配置+条件规则）
- [x] 条件规则数据通路（draft.answerRules → ConfigCompiler → ConsistencyEngine，codec 往返）
- [x] 命中验证码/风控暂停任务（pause_on_aliyun_captcha）
- [x] 配置 UI 深度：多选随机数量、严格比例、维度分组、代理源、验证码暂停、导入/导出
- 验证：26 项单测全绿（新增配置编解码往返 1 项）；测试类路径加入真实 org.json

## 仍未做（明确告知，非阻断）
（已全部补完，见下）

## 收尾波次（已完成）
- [x] 免费 AI 批量模式：BackendClient.freeAiBatch（/batch 提交 + /tasks/{id} 轮询，64题分块），
      SurveyAnswerBuilder 在构建前批量预填，逐题读 state 预填缓存，单题为兜底
- [x] 可视化条件规则编辑器：AnswerRulesScreen（选条件题/模式/选项 → 目标题/动作/选项），
      写入 draft.answerRules → ConfigCompiler → ConsistencyEngine，导入导出往返
- [x] wjx 滑块 min/max/step 解析 + 作答值按范围夹取
- 验证：27 项单测全绿（新增条件规则编解码往返 1 项）

## 桌面专属说明
- Velopack 自动更新：移动端走应用市场/APK 分发，不适用，未移植（唯一保留项）


## 设置页对齐波次（已完成）
- [x] 外观：主题模式（跟随系统/浅色/深色）、动态取色、底部导航文字显隐（即时生效）
- [x] 行为：执行期间屏幕常亮、任务完成/失败通知、提交结果遥测（接通引擎短路）
- [x] 行为：自动保存运行日志 + 保留最近 N 份下拉（3/5/10/20/30/50，对齐 AUTO_SAVE_LOG_RETENTION_OPTIONS）
- [x] 更新：启动时检查更新 + 立即检查（结果见关于页）
- [x] 默认运行参数：默认目标份数 / 默认并发数
- [x] AI 填空：免费 AI / 自定义 API（Base/Key/Model/Prompt）
- [x] 随机 IP 账号：领取试用 / 同步额度 / 卡密兑换 / 服务状态
- [x] 系统工具：运行日志目录信息、导出最近日志、清除日志、恢复默认设置（带确认对话框，对齐 reset_ui_card）
- [x] 日志持久化：RunLogArchiver（任务结束写 files/run_logs，按保留份数自动清理）
- 桌面专属未移植：窗口置顶、关闭前询问保存（平台差异，无对应概念）；配置文件目录在移动端改为应用私有日志目录展示+导出
- 验证：APK 正常产出，全部单测通过（assembleDebug + testDebugUnitTest）

## IP 使用记录页波次（已完成）
- [x] BackendClient.ipUsageSummary（GET /ipzan/usage，递归提取 records + remaining_ip，1:1 io/reports/ip_usage_log.py 容错逻辑）
- [x] MonotoneCubic（Fritsch–Carlson 单调三次插值，1:1 ip_usage_math.compute_monotone_slopes，平滑折线不过冲）
- [x] IpUsageScreen：IP 池剩余卡 + 每日提取 IP 数 Canvas 平滑折线图（面积填充+数据点）+ 日期范围 + 刷新
- [x] 进入页面自动加载 + 触发彩蛋隐藏福利领取（claimBonus，对齐 claim_easter_egg_bonus）
- [x] 入口：设置页“随机 IP 账号”分组 → 查看 IP 使用记录；导航新增 ip_usage 路由
- 桌面专属未移植：彩蛋撒花动画 overlay、注册表 confetti_played 标记（用一次性 bonusMessage 提示等价替代）
- 验证：新增 MonotoneCubicTest（4 项，含线性斜率/平坦段/端点单调/单点安全），全部单测通过；APK 正常产出

## 关于页/社区/联系开发者波次（已完成）
- [x] 关于页重写，1:1 对齐桌面端 about.py + community.py + donate.py：
  - [x] 免责声明警示条（errorContainer 高亮）
  - [x] 版本信息 + 检查更新 + 更新日志/文档/GitHub 链接
  - [x] 社区卡：QQ 群二维码 + 联系开发者 + 参与贡献
  - [x] 支持作者：微信/支付宝赞赏码（对齐 donate.py）
  - [x] 开源许可 GPL-3.0 + 查看协议链接
  - [x] 贡献者列表（6 人，点击跳 GitHub 主页）
  - [x] 服务条款与隐私声明入口（TermsDialog）
  - [x] 版权页脚
- [x] 联系开发者（ContactClient）：multipart POST 到 https://bot.hungrym0.top，
      字段 message/messageType/timestamp/issueTitle/userId，1:1 contact_form message_builder
- [x] ContactDialog：报错反馈/新功能建议/纯聊天 三类型 + 标题 + 邮箱 + 正文，发送状态机
- [x] 服务条款文本移植到 assets/legal/terms_of_service.txt（service_terms + privacy_statement 合并）
- [x] HttpClient 新增 postMultipart（OkHttp MultipartBody）支持文件/字段上传
- [x] DI 注册 ContactClient；消除 Icons.Filled.Send 弃用警告（改 AutoMirrored）
- 验证：APK 正常产出，全部单测通过，无编译警告

## 配置答案深度对齐波次（已完成）
对齐桌面端 question_editor 向导的核心答案配置能力（此前 UI 仅有滑块+单文本框）：
- [x] 倾向预设（偏左/居中/偏右）：TendencyWeights.buildBiasWeights 1:1 复刻 psycho_config.build_bias_weights
      （偏左/右用 8 次曲线压制低端，居中用 3 次曲线），单/下拉/量表/评价题一键生成配比，
      手动拖动滑块自动切回"自定义"
- [x] 填空答案模式选择：自定义/随机姓名/随机手机号/随机身份证/随机整数(范围)/随机文本/AI 生成
      （编译为 __RANDOM_NAME__ / __RANDOM_MOBILE__ / __RANDOM_ID_CARD__ / __RANDOM_INT__:lo:hi / __RANDOM_TEXT__，
      走 RandomText.resolveDynamicToken）
- [x] 自定义填空支持多候选答案（随机选用）+ 增删
- [x] QuestionConfigDraft 扩展 biasPreset/textMode/textIntMin/textIntMax；ConfigCompiler.resolveTextCandidates 映射令牌；
      ConfigCodec 往返持久化新字段
- 验证：新增 TendencyWeightsTest（4 项：偏左单调/左右镜像/居中对称/单选项），全部单测通过；APK 正常产出

## 配置答案交互重做波次（已完成）
修正配置答案交互模型——此前用「联动归一化滑块」（拖一个其余此消彼长），与桌面端不符且体验差。
改为 1:1 对齐桌面端题目向导：
- [x] WeightEditor 组件：每个选项「独立」权重滑块（0–100，互不影响）+ 右侧数字输入框（可键入精确权重）
      + 实时「目标占比」预览（归一化百分比，按高低着色：<10 红 / <20 橙 / <50 黄 / ≥50 绿）+ 顶部分布预览条 + 均分
      （1:1 _compute_ratio_percentages / _pick_ratio_color）
- [x] 单选/下拉/量表/评价题、矩阵题逐行 改用 WeightEditor（独立权重，加权抽样，无需凑满 100）
- [x] 多选题：各选项独立命中概率 滑块 + 数字输入框（0–100%）
- [x] 默认权重改为各 1（独立权重语义）；移除联动占比的 evenSplit100；保留 RatioSliders 供旧测试
- 验证：assembleDebug + testDebugUnitTest 全绿；APK 正常产出

## 顶部白边修复 + UI 美化波次（已完成）
- [x] 修复顶部超大白边：根因是双重状态栏 inset（外层 AppNavigation Scaffold 给 NavHost 加了状态栏 padding，
      内层带 TopAppBar 的页又加一次）。外层 Scaffold 改 contentWindowInsets=0；带 TopAppBar 的页（配置/运行/规则/扫码）
      自行处理顶部；纯 Column 页（首页/设置/关于/IP用量）加 statusBarsPadding
- [x] 配置答案底部「开始执行」改为固定底栏（Surface 阴影 + navigationBarsPadding + 52dp 大按钮，显示份数），
      不再随列表滚动；底部白边随 navigationBarsPadding 收紧
- [x] 主题升级：精修 Light/Dark 配色（surface 分层、primaryContainer、outlineVariant）、统一圆角 Shapes（卡片 16dp）；
      动态取色默认改为关闭，使用品牌蓝 Fluent 配色（贴近桌面端固定主题，避免跟随壁纸发灰）
- [x] LabeledSlider 数值改为高亮药丸徽章；配置页 TopAppBar 显示问卷标题副标题、分组标题「题目配置」、规则入口加图标
- 验证：assembleDebug + testDebugUnitTest 全绿；APK 正常产出

## 比例机制修复波次（已完成）
根因：桌面端 is_strict_custom_ratio_mode —— 只要设了自定义配比就「自动严格收敛」（运行时分布矫正使实际结果贴近设定比例）。
而之前 Android 把「严格比例」做成手动开关且默认关，导致默认每份独立抽样、方差大、比例不准。
- [x] QuestionConfigDraft：用 distributionMode（random/custom）取代手动 strictRatio
- [x] ConfigCompiler：custom 模式自动 strictMap=true（→ buildSingle/Dropdown/Matrix 触发 DistributionCorrection 收敛 +
      enforceReferenceRankOrder 保序）；random 模式发 -1（等概率随机）
- [x] ConfigureScreen：单选/下拉/量表/评价/矩阵 顶部加「完全随机 / 自定义配比」模式切换；
      仅自定义模式显示倾向预设 + 权重编辑器；移除手动「严格比例」开关
- [x] 「全部均分」=自定义+等权重（严格收敛到均等）；「全部随机」=完全随机模式
- [x] ConfigCodec：distribution_mode 持久化 + 兼容旧 strict_ratio 字段（true→custom）
- 验证：assembleDebug + testDebugUnitTest 全绿；APK 正常产出

## 占比编辑器改为百分比语义（已完成）
反馈：滑块用抽象「权重」(每项上限恒 100，两项各 100 却显示 50%) 不直观。
改为「百分比/占比」语义，符合用户心智：
- [x] WeightEditor 重做：数字框直接是该选项占比%，所有选项合计=100%；顶部显示「合计 X%」
      （=100 绿 / ≠100 橙）+「均分」+「归一化到 100%」一键操作
- [x] 默认权重按选项数均分百分比（2 项→50/50，5 项→20×5），不再全是 1
- [x] 倾向预设应用后归一化到 100%；「全部均分」用百分比均分
- [x] 运行时仍按占比加权抽样并向目标收敛（引擎本就归一化，合计非恰好 100 也可）
- 验证：assembleDebug + testDebugUnitTest 全绿；APK 正常产出

## 反人类交互修复波次（已完成）
- [x] 占比改为「联动」：拖动/输入任一项 → 其余按原比例自动补足，合计恒 100%（复用 redistribute），
      去掉多余的「归一化」按钮；滑块 + 数字框都联动，既能拖也能精确键入
- [x] 份数/并发/作答时长 用 Stepper（− 数字框 + ）取代大范围难拖准的滑块；
      份数 1–9999 可直接键入，时长 5 秒步进，配置页 + 设置页默认运行参数同步
- 验证：assembleDebug + testDebugUnitTest 全绿；APK 正常产出

## 版本号对齐桌面端（已完成）
- [x] Android versionName 3.2.0 → 4.0.2，versionCode 1 → 402，对齐桌面端 software/app/version.py（__VERSION__=4.0.2）
- [x] AppVersion.VERSION → 4.0.2；提交结果上报 client_version、联系开发者来源版本、关于页显示均走 AppVersion.VERSION，全局一致
- 说明：本地 software/ 即桌面端 4.0.2 最新源码，Android 各模块一直对照它移植；
  之前显示 3.2.0 导致「检查更新」误判落后，现已对齐。若 GitHub 有比 4.0.2 更新的版本，需要拿到新版源码才能 diff 同步
- 验证：assembleDebug 通过，APK 正常产出
