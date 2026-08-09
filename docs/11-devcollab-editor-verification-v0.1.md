# 11 ContextWeave 编辑器验收基线 V0.1

## 1. 目标与范围

验证 `PARAGRAPH / HEADING / CODE / TODO` 四类业务 Block 从浏览器编辑器，经 Collaboration Gateway 与 Core 保存、重新读取、发布快照展示的完整链路；同时建立多 Editor 首屏基线。本文记录验收口径，不记录本地账号与临时数据。

## 2. 功能验收口径

| 场景 | 通过条件 |
|---|---|
| 段落 | 修改正文后版本递增，重新进入工作台内容一致 |
| 标题 | 可切换 H1/H2/H3，重新读取仍保持 level |
| 代码 | 换行和空格进入 `codeBlock`，保存后版本递增 |
| 待办 | 文本和 checked 状态同时保存并可回读 |
| 发布快照 | V1 快照按段落、标题、代码、只读复选框渲染，不使用 `v-html` |
| 显式保存 | 鼠标点击与键盘激活均触发标准 `click` 语义 |

## 3. 结构契约边界

编辑器输出在发送前必须规范化为 `10-devcollab-structured-block-contract-v0.2.md` 定义的白名单结构。Tiptap 扩展自动补充的编辑器默认属性不得直接扩大服务端契约；当前 `codeBlock.attrs.language = null` 必须在客户端序列化边界移除。

## 4. 性能基线

使用 `tools/benchmark/editor-benchmark-seed.mjs` 创建可重复的四类型混合数据集：

```powershell
node tools/benchmark/editor-benchmark-seed.mjs `
  --base-url http://localhost:8080 `
  --frontend-url http://localhost:5173 `
  --blocks 50
```

本机首次样本（2026-07-19）：

| 指标 | 结果 |
|---|---:|
| 数据生成 | 50 Block / 1850.29 ms |
| 文档点击至 50 个 Editor 可用 | 2183 ms |
| 页面 DOM 节点 | 2290 |

当前结果作为基线，不宣称跨机器通用。MVP 暂不引入可视区挂载；当同一口径连续三轮 P95 超过 2500 ms，或目标文档规模超过 50 Block 时，再评审虚拟列表/按可视区挂载。

## 5. 验收结论

四类 Block 的编辑、Gateway 保存、Core 严格校验、回读及 V1 版本快照链路通过。浏览器联调发现并修复了 Tiptap 默认属性与服务端白名单不一致，以及保存按钮仅监听 `mousedown` 导致键盘激活无效的问题。

