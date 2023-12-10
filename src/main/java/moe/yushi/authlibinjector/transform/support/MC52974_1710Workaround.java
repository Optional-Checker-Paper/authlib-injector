/*
 * Copyright (C) 2022  Haowei Wen <yushijinhun@gmail.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package moe.yushi.authlibinjector.transform.support;

import static moe.yushi.authlibinjector.util.Logging.log;
import static moe.yushi.authlibinjector.util.Logging.Level.INFO;
import static moe.yushi.authlibinjector.util.Logging.Level.WARNING;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import moe.yushi.authlibinjector.AuthlibInjector;
import moe.yushi.authlibinjector.transform.CallbackMethod;
import moe.yushi.authlibinjector.transform.TransformContext;
import moe.yushi.authlibinjector.transform.TransformUnit;
import moe.yushi.authlibinjector.util.WeakIdentityHashMap;

public class MC52974_1710Workaround {
	private MC52974_1710Workaround() {
	}

	public static void init() {
		MainArgumentsTransformer.getVersionSeriesListeners().add(version -> {
			if ("1.7.10".equals(version)) {
				log(INFO, "Enable MC-52974 Workaround for 1.7.10");
				AuthlibInjector.getClassTransformer().units.add(new SessionTransformer());
				AuthlibInjector.getClassTransformer().units.add(new S0CPacketSpawnPlayerTransformer());
				AuthlibInjector.retransformClasses("bbs", "net.minecraft.util.Session", "gb", "net.minecraft.network.play.server.S0CPacketSpawnPlayer");
			}
		});
	}

	// Empty GameProfile -> Filled GameProfile?
	private static final Map<Object, Optional<Object>> markedGameProfiles = new WeakIdentityHashMap<>();

	@CallbackMethod
	public static void markGameProfile(Object gp) {
		synchronized (markedGameProfiles) {
			markedGameProfiles.putIfAbsent(gp, Optional.empty());
		}
	}

	@SuppressWarnings("optional:optional.null.comparison")  // optional-null-comparison
	@CallbackMethod
	public static Object accessGameProfile(Object gp, Object minecraftServer, boolean isNotchName) {
		synchronized (markedGameProfiles) {
			Optional<Object> value = markedGameProfiles.get(gp);
			if (value != null) {
				if (value.isPresent()) {
					return value.get();
				}

				// query it
				if (minecraftServer != null) {
					log(INFO, "Filling properties for " + gp);
					try {
						ClassLoader cl = minecraftServer.getClass().getClassLoader();

						Class<?> classGameProfile = cl.loadClass("com.mojang.authlib.GameProfile");
						Object gameProfile = classGameProfile.getConstructor(UUID.class, String.class)
								.newInstance(
										classGameProfile.getMethod("getId").invoke(gp),
										classGameProfile.getMethod("getName").invoke(gp));

						Class<?> classMinecraftServer = cl.loadClass("net.minecraft.server.MinecraftServer");
						Object minecraftSessionService = (isNotchName
								? classMinecraftServer.getMethod("av")
								: classMinecraftServer.getMethod("func_147130_as"))
										.invoke(minecraftServer);

						Object filledGameProfile = cl.loadClass("com.mojang.authlib.minecraft.MinecraftSessionService").getMethod("fillProfileProperties", classGameProfile, boolean.class)
								.invoke(minecraftSessionService, gameProfile, true);

						markedGameProfiles.put(gp, Optional.of(filledGameProfile));
						return filledGameProfile;
					} catch (ReflectiveOperationException e) {
						log(WARNING, "Failed to inject GameProfile properties", e);
					}
				}
			}
		}
		return gp;
	}

	private static class SessionTransformer implements TransformUnit {
		@Override
		public Optional<ClassVisitor> transform(ClassLoader classLoader, String className, ClassVisitor writer, TransformContext ctx) {
			return detectNotchName(className, "bbs", "net.minecraft.util.Session", isNotchName -> new ClassVisitor(ASM9, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					if (isNotchName
							? "e".equals(name) && "()Lcom/mojang/authlib/GameProfile;".equals(descriptor)
							: "func_148256_e".equals(name) && "()Lcom/mojang/authlib/GameProfile;".equals(descriptor)) {

						return new MethodVisitor(ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
							@Override
							public void visitInsn(int opcode) {
								if (opcode == ARETURN) {
									ctx.markModified();
									super.visitInsn(DUP);
									ctx.invokeCallback(mv, MC52974_1710Workaround.class, "markGameProfile");
								}
								super.visitInsn(opcode);
							}
						};
					} else {
						return super.visitMethod(access, name, descriptor, signature, exceptions);
					}
				}
			});
		}

		@Override
		public String toString() {
			return "1.7.10 MC-52974 Workaround (Session)";
		}
	}

	private static class S0CPacketSpawnPlayerTransformer implements TransformUnit {
		@Override
		public Optional<ClassVisitor> transform(ClassLoader classLoader, String className, ClassVisitor writer, TransformContext ctx) {
			return detectNotchName(className, "gb", "net.minecraft.network.play.server.S0CPacketSpawnPlayer", isNotchName -> new ClassVisitor(ASM9, writer) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
					if (isNotchName
							? "b".equals(name) && "(Let;)V".equals(descriptor)
							: "func_148840_b".equals(name) && "(Lnet/minecraft/network/PacketBuffer;)V".equals(descriptor)) {

						return new MethodVisitor(ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
							@Override
							public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
								super.visitFieldInsn(opcode, owner, name, descriptor);

								if (opcode == GETFIELD && (isNotchName
										? "gb".equals(owner) && "b".equals(name) && "Lcom/mojang/authlib/GameProfile;".equals(descriptor)
										: "net/minecraft/network/play/server/S0CPacketSpawnPlayer".equals(owner) && "field_148955_b".equals(name) && "Lcom/mojang/authlib/GameProfile;".equals(descriptor))) {
									ctx.markModified();
									if (isNotchName) {
										super.visitMethodInsn(INVOKESTATIC, "net/minecraft/server/MinecraftServer", "I", "()Lnet/minecraft/server/MinecraftServer;", false);
									} else {
										super.visitMethodInsn(INVOKESTATIC, "net/minecraft/server/MinecraftServer", "func_71276_C", "()Lnet/minecraft/server/MinecraftServer;", false);
									}
									super.visitLdcInsn(isNotchName ? 1 : 0);
									ctx.invokeCallback(mv, MC52974_1710Workaround.class, "accessGameProfile");
									super.visitTypeInsn(CHECKCAST, "com/mojang/authlib/GameProfile");
								}
							}
						};
					} else {
						return super.visitMethod(access, name, descriptor, signature, exceptions);
					}
				}
			});
		}

		@Override
		public String toString() {
			return "1.7.10 MC-52974 Workaround (S0CPacketSpawnPlayer)";
		}
	}

	private static <T> Optional<T> detectNotchName(String input, String ifNotchName, String ifDeobf, Function<Boolean, T> callback) {
		if (ifNotchName.equals(input)) {
			return Optional.of(callback.apply(true));
		} else if (ifDeobf.equals(input)) {
			return Optional.of(callback.apply(false));
		} else {
			return Optional.empty();
		}
	}
}
