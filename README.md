# Minecraft-Reflex

此mod在Minecraft中实现Nvidia Reflex，降低渲染延迟。

选项中有两个选项，一个是切换开关，一个是减少等待时间。
减少等待时间以纳秒为单位，如果启用reflex后gpu占有率下降，可增加一些数值使gpu占用率上升，使得gpu占用率刚刚好达到100%，如果增加数值过头会增加延迟。
有时可能算法估计错误导致延迟过短造成渲染队列堆积，渲染延迟上升，可使用负数数值增加等待时间，减少延迟。

存在问题：测量cpu时间的终点本应在gpu命令进入渲染队列的时间点前面一点，但是cpu时间的终点的时间戳却在进入渲染队列的时间点后面，导致延迟上升，我打了个不完美的补丁解决这个问题，会的人可以解决下这个问题

# Minecraft-Reflex

This mod implements Nvidia Reflex in Minecraft to reduce rendering latency.

There are two options in the settings: one is a toggle switch, and the other is to reduce waiting time.
The waiting time is reduced in nanoseconds. If enabling Reflex causes a drop in GPU utilization, you can increase the value slightly to raise the GPU usage to around 100%. However, increasing the value too much will lead to higher latency.
Sometimes, the algorithm may estimate incorrectly, resulting in latency being too short and causing rendering queues to pile up, which increases rendering latency. In such cases, you can use negative values to increase the waiting time and reduce the latency.

Problem: The end point of measuring cpu time should be a little before the time when gpu command enters the rendering queue, but the timestamp of the end point of cpu time is after the time when it enters the rendering queue, which leads to increased latency. I have made an imperfect patch to solve this problem. Anyone who knows how can solve this problem?
