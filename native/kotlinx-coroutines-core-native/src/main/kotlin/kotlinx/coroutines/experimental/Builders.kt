/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental

import kotlin.coroutines.experimental.*

/**
 * Runs new coroutine and **blocks** current thread _interruptibly_ until its completion.
 * This function should not be used from coroutine. It is designed to bridge regular blocking code
 * to libraries that are written in suspending style, to be used in `main` functions and in tests.
 *
 * The default [CoroutineDispatcher] for this builder in an implementation of [EventLoop] that processes continuations
 * in this blocked thread until the completion of this coroutine.
 * See [CoroutineDispatcher] for the other implementations that are provided by `kotlinx.coroutines`.
 *
 * When [CoroutineDispatcher] is explicitly specified in the [context], then the new coroutine runs in the context of
 * the specified dispatcher while the current thread is blocked. If the specified dispatcher implements [EventLoop]
 * interface and this `runBlocking` invocation is performed from inside of the this event loop's thread, then
 * this event loop is processed using its [processNextEvent][EventLoop.processNextEvent] method until coroutine completes.
 *
 * If this blocked thread is interrupted (see [Thread.interrupt]), then the coroutine job is cancelled and
 * this `runBlocking` invocation throws [InterruptedException].
 *
 * See [newCoroutineContext] for a description of debugging facilities that are available for newly created coroutine.
 *
 * @param context context of the coroutine. The default value is an implementation of [EventLoop].
 * @param block the coroutine code.
 */
public fun <T> runBlocking(context: CoroutineContext = EmptyCoroutineContext, block: suspend CoroutineScope.() -> T): T {
    val contextInterceptor = context[ContinuationInterceptor]
    val privateEventLoop = contextInterceptor == null // create private event loop if no dispatcher is specified
    val eventLoop = if (privateEventLoop) {
        check(currentEventLoop == null) { "Cannot use runBlocking inside runBlocking" }
        val newEventLoop = BlockingEventLoop()
        currentEventLoop = newEventLoop
        newEventLoop
    } else
        contextInterceptor as? EventLoop
    val newContext = newCoroutineContext(
        if (privateEventLoop) context + (eventLoop as ContinuationInterceptor) else context
    )
    val coroutine = BlockingCoroutine<T>(newContext, eventLoop, privateEventLoop)
    coroutine.start(CoroutineStart.DEFAULT, coroutine, block)
    return coroutine.joinBlocking().also {
        currentEventLoop = null
    }
}

private class BlockingCoroutine<T>(
    parentContext: CoroutineContext,
    private val eventLoop: EventLoop?,
    private val privateEventLoop: Boolean
) : AbstractCoroutine<T>(parentContext, true) {
    init {
        if (privateEventLoop) require(eventLoop is BlockingEventLoop)
    }

    @Suppress("UNCHECKED_CAST")
    fun joinBlocking(): T {
        while (true) {
            val parkNanos = eventLoop?.processNextEvent() ?: Long.MAX_VALUE
            // note: process next even may loose unpark flag, so check if completed before parking
            if (isCompleted) break
            // todo: LockSupport.parkNanos(this, parkNanos)
        }
        // process queued events (that could have been added after last processNextEvent and before cancel
        if (privateEventLoop) (eventLoop as BlockingEventLoop).apply {
            // We exit the "while" loop above when this coroutine's state "isCompleted",
            // Here we should signal that BlockingEventLoop should not accept any more tasks
            isCompleted = true
            shutdown()
        }
        // now return result
        val state = this.state
        (state as? CompletedExceptionally)?.let { throw it.cause }
        return state as T
    }
}