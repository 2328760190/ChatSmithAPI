这是一个OpenAI 类服务端程序

由👇分析而来

	https://play.google.com/store/apps/details?id=com.smartwidgetlabs.chatgpt


本项目是一个类 OpenAI 服务端程序，模拟OpenAI API标准的响应，无需提供Authorization，可与多种前端应用（如 NextChat、ChatBox 等）无缝集成


只支持支持gpt-4o-mini-2024-07-18✅

传其他的模型返回的还是4o-mini，他的API只支持非流式传输，虽然本项目实现了stream，但事实上是一次性全部回传的，效果只能将就

几乎无限使用，几乎没有频率限制

对话支持识图：

	/v1/chat/completions

 生成图片：调用的是他们的API，但是本质是SDXL模型

 	/v1/images/generations

识图如果尺寸大小错过限制，自动等比例缩小
