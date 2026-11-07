# libs/ 第三方依赖

**当前为空(2026-11-03 R7 起)。** Shizuku 运行时已完全自包含:
provider 是自实现的 `com.ggbot.app.ShizukuGate`(协议:server call "sendBinder" 投递 BinderContainer),
binder 事务(newProcess=8 等)由 `ShizukuHeal` 手写 Parcel 直发,
唯一的上游类 `moe.shizuku.api.BinderContainer`(45 行 Parcelable)已并入 `src/moe/shizuku/api/`,
出处与改注说明见该文件头。

历史:曾引入 `dev.rikka.shizuku:api` 的残缺 jar(缺 AIDL stub,rikka.shizuku.Shizuku
类加载即 NoClassDefFoundError 且进程内永久缓存)与本地编译的 provider classes;
R7 起全部移除,构建不再需要 LIBS classpath(gate.sh/build.sh 的 find libs 保留,
向后兼容未来依赖)。

## 缺类即闪退的教训(给下一轮)

清单里声明的任何 `android:name="..."` 类都必须在 dex 里——provider 在 `handleBindApplication`
阶段实例化,比任何 Activity/测试都早;JVM 门闸(gate.sh)不加载清单,覆盖不到这类缺陷。
出包后自查:`dexdump build/dex/classes.dex | grep -c '<清单里声明的类名>'`。
