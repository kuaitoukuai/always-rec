package com.daluo.alwaysrec

import kotlinx.coroutines.sync.Mutex

/**
 * 进程内的串行锁。
 *
 * WorkManager 的多个 Worker 默认跑在同一个进程里，但彼此独立调度：
 * 上传 Worker 和转写 Worker 可能同时判断"这段还没有文字"，于是同一段音频
 * 被调用两次 ASR —— 两份钱、两份结果、后写的覆盖先写的。
 *
 * 用 Mutex 而不是 synchronized：协程在锁内挂起后可能换线程恢复，
 * 线程级锁会失效，协程级锁不会。
 *
 * 两个锁而不是一把，是为了让"转写"和"上传"仍能各自推进，
 * 只在真正互斥的临界区串行。取锁顺序固定为 upload → asr，避免死锁。
 */
object WorkLock {
    /** 转写临界区：同一时刻只有一个 Worker 在跑 ASR。 */
    val asr = Mutex()

    /** 上传临界区：避免周期任务、WiFi 任务、手动任务同时上传同一批文件。 */
    val upload = Mutex()
}
