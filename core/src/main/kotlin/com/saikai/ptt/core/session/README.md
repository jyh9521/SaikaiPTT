# core.session

PTT 会话状态机：发送端 IDLE→REQUESTING→TRANSMITTING→ENDING，
接收端 IDLE→RECEIVING→ENDING，以及 INTERRUPTED 与各 FAILED 分支。

会话所有权的原子转移（Busy 与强插的仲裁）也在这里，
见 `docs/03_Protocol.md` §33、§34。

由 **Task19** 填充。
