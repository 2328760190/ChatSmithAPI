这是一个OpenAI 类服务端程序

由👇分析而来

	https://play.google.com/store/apps/details?id=com.smartwidgetlabs.chatgpt


本项目是一个类 OpenAI 服务端程序，模拟OpenAI API标准的响应，无需提供Authorization，可与多种前端应用（如 NextChat、ChatBox 等）无缝集成


只支持支持gpt-4o-mini-2024-07-18✅

传其他的模型返回的还是4o-mini，他的API只支持非流式传输，虽然本项目实现了stream，但事实上是一次性全部回传的，效果只能将就

需要其他模型可移步本人另一个项目👉https://github.com/patchescamerababy/ChatOnAPI

几乎无限使用，几乎没有频率限制

支持对话、识图

	/v1/chat/completions

识图需要在当前目录创建文件夹，如果尺寸大小错过限制，自动等比例缩小
