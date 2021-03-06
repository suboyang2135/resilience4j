/*
 * Copyright 2017 Jan Sykora
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.resilience4j.ratpack.bulkhead

import io.github.resilience4j.bulkhead.BulkheadRegistry
import io.github.resilience4j.ratpack.Resilience4jModule
import io.github.resilience4j.ratpack.recovery.RecoveryFunction
import ratpack.exec.Promise
import ratpack.http.client.ReceivedResponse
import ratpack.test.embed.EmbeddedApp
import ratpack.test.http.TestHttpClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Unroll

import java.lang.reflect.UndeclaredThrowableException
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Function

import static ratpack.groovy.test.embed.GroovyEmbeddedApp.ratpack

@IgnoreIf({ env.TRAVIS })
@Unroll
class BulkheadSpec extends Specification {

    @AutoCleanup
    EmbeddedApp app

    @AutoCleanup(value = "shutdown")
    ExecutorService executor = Executors.newCachedThreadPool()

    @Delegate
    TestHttpClient client

    def "test no bulkhead registry installed, app still works"() {
        given:
        app = ratpack {
            bindings {
                module(Resilience4jModule)
                bind(Something)
            }
            handlers {
                get('promise') { Something something ->
                    something.simpleBulkheadPromise().then {
                        render it
                    }
                }
            }
        }
        client = testHttpClient(app)

        when:
        def actual = get('promise')

        then:
        actual.body.text == 'bulkhead promise'
        actual.statusCode == 200
    }

    def "test bulkhead a method via annotation - path=#path"() {
        given:
        BulkheadRegistry registry = BulkheadRegistry.of(buildConfig())
        def latch = new CountDownLatch(1)
        def blockLatch = new CountDownLatch(1)
        app = ratpack {
            bindings {
                bindInstance(BulkheadRegistry, registry)
                bind(Something)
                module(Resilience4jModule)
            }
            handlers {
                get('promise') { Something something ->
                    something.bulkheadPromise(latch, blockLatch).then {
                        render it
                    }
                }
                get('Flux') { Something something ->
                    something.bulkheadFlux(latch, blockLatch).subscribe {
                        render it
                    } {
                        response.status(500).send(it.toString())
                    }
                }
                get('Mono') { Something something ->
                    something.bulkheadMono(latch, blockLatch).subscribe({
                        render it
                    } as Consumer<String>) {
                        response.status(500).send(it.toString())
                    }
                }
                get('stage') { Something something ->
                    render something.bulkheadStage(latch, blockLatch).toCompletableFuture().get()
                }
                get('normal') { Something something ->
                    render something.bulkheadNormal(latch, blockLatch)
                }
            }
        }
        client = testHttpClient(app)

        when:
        def blockedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        and:
        assert blockLatch.await(30, TimeUnit.SECONDS)
        def rejectedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        and:
        rejectedResponse.get(5, TimeUnit.SECONDS)
        latch.countDown() // unblock blocked response
        def permittedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        then:
        blockedResponse.get().statusCode == 200
        rejectedResponse.get().body.text.contains("io.github.resilience4j.bulkhead.BulkheadFullException: Bulkhead 'test' is full")
        rejectedResponse.get().statusCode == 500
        permittedResponse.get().statusCode == 200

        where:
        path << [
                'promise',
                'Flux',
                'Mono',
                'stage',
                'normal'
        ]
    }

    def "test bulkhead a method via annotation with exception - path=#path"() {
        given:
        BulkheadRegistry registry = BulkheadRegistry.of(buildConfig())
        def latch = new CountDownLatch(1)
        def blockLatch = new CountDownLatch(1)
        app = ratpack {
            bindings {
                bindInstance(BulkheadRegistry, registry)
                bind(Something)
                module(Resilience4jModule)
            }
            handlers {
                get('promise') { Something something ->
                    something.bulkheadPromiseException(latch, blockLatch).then {
                        render it
                    }
                }
                get('Flux') { Something something ->
                    something.bulkheadFluxException(latch, blockLatch).subscribe {
                        render it
                    } {
                        response.status(500).send(it.toString())
                    }
                }
                get('Mono') { Something something ->
                    something.bulkheadMonoException(latch, blockLatch).subscribe({
                        render it
                    } as Consumer<Void>) {
                        response.status(500).send(it.toString())
                    }
                }
                get('stage') { Something something ->
                    try {
                        render something.bulkheadStageException(latch, blockLatch).toCompletableFuture().get()
                    } catch (ExecutionException e) {
                        throw e.getCause() // unwrap exception
                    }
                }
                get('normal') { Something something ->
                    render something.bulkheadNormalException(latch, blockLatch)
                }
            }
        }
        client = testHttpClient(app)

        when:
        def blockedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        and:
        assert blockLatch.await(30, TimeUnit.SECONDS)
        def rejectedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        and:
        rejectedResponse.get(5, TimeUnit.SECONDS)
        latch.countDown() // unblock blocked response
        def permittedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        then:
        blockedResponse.get().body.text.contains(message)
        blockedResponse.get().statusCode == 500
        rejectedResponse.get().body.text.contains("io.github.resilience4j.bulkhead.BulkheadFullException: Bulkhead 'test' is full")
        rejectedResponse.get().statusCode == 500
        permittedResponse.get().body.text.contains(message)
        permittedResponse.get().statusCode == 500

        where:
        path      | message
        'promise' | 'bulkhead promise exception'
        'Flux'    | 'bulkhead Flux exception'
        'Mono'    | 'bulkhead Mono exception'
        'stage'   | 'bulkhead stage exception'
        'normal'  | 'bulkhead normal exception'
    }

    def "test rate limit a method via annotation with fallback - path=#path"() {
        given:
        BulkheadRegistry registry = BulkheadRegistry.of(buildConfig())
        def latch = new CountDownLatch(1)
        def blockLatch = new CountDownLatch(1)
        app = ratpack {
            bindings {
                bindInstance(BulkheadRegistry, registry)
                bind(Something)
                module(Resilience4jModule)
            }
            handlers {
                get('promise') { Something something ->
                    something.bulkheadPromiseFallback(latch, blockLatch).then {
                        render it
                    }
                }
                get('Flux') { Something something ->
                    something.bulkheadFluxFallback(latch, blockLatch).subscribe {
                        render it
                    }
                }
                get('Mono') { Something something ->
                    something.bulkheadMonoFallback(latch, blockLatch).subscribe({
                        render it
                    } as Consumer<Void>)
                }
                get('stage') { Something something ->
                    render something.bulkheadStageFallback(latch, blockLatch).toCompletableFuture().get()
                }
                get('normal') { Something something ->
                    render something.bulkheadNormalFallback(latch, blockLatch)
                }
            }
        }
        client = testHttpClient(app)

        when:
        def blockedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        and:
        assert blockLatch.await(30, TimeUnit.SECONDS)
        def rejectedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        and:
        rejectedResponse.get(5, TimeUnit.SECONDS)
        latch.countDown() // unblock blocked response
        def permittedResponse = executor.submit({
            client.get(path)
        } as Callable<ReceivedResponse>)

        then:
        blockedResponse.get().body.text == "recovered"
        blockedResponse.get().statusCode == 200
        rejectedResponse.get().body.text == "recovered"
        rejectedResponse.get().statusCode == 200
        permittedResponse.get().body.text == "recovered"
        permittedResponse.get().statusCode == 200

        where:
        path << [
                'promise',
                'Flux',
                'Mono',
                'stage',
                'normal'
        ]
    }

    // 1 concurrent call
    def buildConfig() {
        io.github.resilience4j.bulkhead.BulkheadConfig.custom()
                .maxConcurrentCalls(1)
                .maxWaitTime(0)
                .build()
    }

    // both latches are unblocked on later calls
    static class Something {

        @Bulkhead(name = "test")
        Promise<String> simpleBulkheadPromise() {
            Promise.async {
                it.success("bulkhead promise")
            }
        }

        @Bulkhead(name = "test")
        Promise<String> bulkheadPromise(CountDownLatch latch, CountDownLatch blockLatch) {
            Promise.async {
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                it.success("bulkhead promise")
            }
        }

        @Bulkhead(name = "test")
        Flux<String> bulkheadFlux(CountDownLatch latch, CountDownLatch blockLatch) {
            Flux.just("bulkhead Flux").map({ it ->
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                it
            } as Function<String, String>)
        }

        @Bulkhead(name = "test")
        Mono<String> bulkheadMono(CountDownLatch latch, CountDownLatch blockLatch) {
            Mono.just("bulkhead Mono").map({ it ->
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                it
            } as Function<String, String>)
        }

        @Bulkhead(name = "test")
        CompletionStage<String> bulkheadStage(CountDownLatch latch, CountDownLatch blockLatch) {
            CompletableFuture.supplyAsync {
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                'bulkhead stage'
            }
        }

        @Bulkhead(name = "test")
        String bulkheadNormal(CountDownLatch latch, CountDownLatch blockLatch) {
            blockLatch.countDown()
            assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
            "bulkhead normal"
        }

        @Bulkhead(name = "test")
        Promise<String> bulkheadPromiseException(CountDownLatch latch, CountDownLatch blockLatch) {
            Promise.async {
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                it.error(new Exception("bulkhead promise exception"))
            }
        }

        @Bulkhead(name = "test")
        Flux<Void> bulkheadFluxException(CountDownLatch latch, CountDownLatch blockLatch) {
            Flux.just("bulkhead Flux").map({
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                throw new Exception("bulkhead Flux exception")
            } as Function<String, Void>).onErrorMap(UndeclaredThrowableException) {
                it.undeclaredThrowable
            }
        }

        @Bulkhead(name = "test")
        Mono<Void> bulkheadMonoException(CountDownLatch latch, CountDownLatch blockLatch) {
            Mono.just("bulkhead Mono").map({
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                throw new Exception("bulkhead Mono exception")
            } as Function<String, Void>).onErrorMap(UndeclaredThrowableException) {
                it.undeclaredThrowable
            }
        }

        @Bulkhead(name = "test")
        CompletionStage<Void> bulkheadStageException(CountDownLatch latch, CountDownLatch blockLatch) {
            CompletableFuture.supplyAsync {
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                throw new Exception('bulkhead stage exception')
            }
        }

        @Bulkhead(name = "test")
        String bulkheadNormalException(CountDownLatch latch, CountDownLatch blockLatch) {
            blockLatch.countDown()
            assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
            throw new Exception("bulkhead normal exception")
        }

        @Bulkhead(name = "test", recovery = MyRecoveryFunction)
        Promise<String> bulkheadPromiseFallback(CountDownLatch latch, CountDownLatch blockLatch) {
            Promise.async {
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                it.error(new Exception("bulkhead promise exception"))
            }
        }

        @Bulkhead(name = "test", recovery = MyRecoveryFunction)
        Flux<Void> bulkheadFluxFallback(CountDownLatch latch, CountDownLatch blockLatch) {
            Flux.just("bulkhead Flux").map({
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                throw new Exception("bulkhead Flux exception")
            } as Function<String, Void>)
        }

        @Bulkhead(name = "test", recovery = MyRecoveryFunction)
        Mono<Void> bulkheadMonoFallback(CountDownLatch latch, CountDownLatch blockLatch) {
            Mono.just("bulkhead Mono").map({
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                throw new Exception("bulkhead Mono exception")
            } as Function<String, Void>)
        }

        @Bulkhead(name = "test", recovery = MyRecoveryFunction)
        CompletionStage<Void> bulkheadStageFallback(CountDownLatch latch, CountDownLatch blockLatch) {
            CompletableFuture.supplyAsync {
                blockLatch.countDown()
                assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
                throw new Exception('bulkhead stage exception')
            }
        }

        @Bulkhead(name = "test", recovery = MyRecoveryFunction)
        String bulkheadNormalFallback(CountDownLatch latch, CountDownLatch blockLatch) {
            blockLatch.countDown()
            assert latch.await(30, TimeUnit.SECONDS): "Timeout - test failure"
            throw new Exception("bulkhead normal exception")
        }
    }

    static class MyRecoveryFunction implements RecoveryFunction<String> {
        @Override
        String apply(Throwable t) throws Exception {
            "recovered"
        }
    }
}
