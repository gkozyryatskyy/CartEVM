package com.hedera.cartevm;

/*-
 * ‌
 * CartEVM
 * ​
 * Copyright (C) 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import static com.hedera.cartevm.Step.RETURN_CONTRACT_ADDRESS;
import static com.hedera.cartevm.Step.REVERT_CONTRACT_ADDRESS;

import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.math.BigInteger;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.MainnetEVMs;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeFactory;
import org.hyperledger.besu.evm.fluent.SimpleBlockValues;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.gascalculator.LondonGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.precompile.MainnetPrecompiledContracts;
import org.hyperledger.besu.evm.precompile.PrecompileContractRegistry;
import org.hyperledger.besu.evm.processor.ContractCreationProcessor;
import org.hyperledger.besu.evm.processor.MessageCallProcessor;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

public class LocalRunner extends CodeGenerator {

  public static final String OUT_FORMAT = "%20s\t%20s\t%,20d\t%,20d\t%,20.0f\t%,20d\t%,20d\t%s%n";
  public static final String HEADER_OUT_FORMAT = "%20s\t%20s\t%20s\t%20s\t%20s\t%20s\t%20s\t%s"
          .formatted("OP Name", "STATUS", "GAS", "TIME NS", "GAS^9 per NS", "OPs", "NS per OP", "REVERT Reason");
  public static final String CUMULATIVE_OUT_FORMAT = "%20s\t%20s\t%,20d\t%,20d\t%,20.0f\t%n";

  static final Address SENDER = Address.fromHexString("12345678");
  static final Address RECEIVER = Address.fromHexString("9abcdef0");
  final LoadingCache<String, String> bytecodeCache =
      CacheBuilder.newBuilder().initialCapacity(30_000).build(CacheLoader.from(this::compileYul));
  static long cumulativeGas = 0L;
  static long cumulativeNanos = 0L;

  public LocalRunner(List<Step> steps, long gasLimit, int sizeLimit) {
    super(steps, gasLimit, sizeLimit);
  }

  public void prexistingState(WorldUpdater worldUpdater, Bytes codeBytes) {
    worldUpdater.getOrCreate(SENDER).setBalance(Wei.of(BigInteger.TWO.pow(20)));

    MutableAccount receiver = worldUpdater.getOrCreate(RECEIVER);
    receiver.setCode(codeBytes);
    // for sload
    receiver.setStorageValue(UInt256.fromHexString("54"), UInt256.fromHexString("99"));

    MutableAccount otherAccount =
        worldUpdater.getOrCreate(Address.fromHexString(RETURN_CONTRACT_ADDRESS));
    // for balance
    otherAccount.setBalance(Wei.fromHexString("0x0ba1a9ce0ba1a9ce"));
    // for extcode*, returndata*, and call*
    otherAccount.setCode(Bytes.fromHexString("0x3360005260206000f3"));

    MutableAccount revert =
        worldUpdater.getOrCreate(Address.fromHexString(REVERT_CONTRACT_ADDRESS));
    revert.setBalance(Wei.fromHexString("0x0ba1a9ce0ba1a9ce"));
    // for REVERT
    revert.setCode(Bytes.fromHexString("0x6055605555604360a052600160a0FD"));
  }

  public void execute(boolean verbose) {
    GeneratedCode yul = generate(yulTemplate);
    String bytecode = bytecodeCache.getUnchecked(yul.code());
    Bytes codeBytes = Bytes.fromHexString(bytecode);

    WorldUpdater worldUpdater = new SimpleWorld();
    prexistingState(worldUpdater, codeBytes);

    // final EVM evm = MainnetEvms.london();
    GasCalculator londonGasCalculator = new LondonGasCalculator();
    final EVM evm =
        MainnetEVMs.london(londonGasCalculator, BigInteger.TEN, EvmConfiguration.DEFAULT);
    final PrecompileContractRegistry precompileContractRegistry = new PrecompileContractRegistry();
    MainnetPrecompiledContracts.populateForIstanbul(
        precompileContractRegistry, londonGasCalculator);
    final Stopwatch stopwatch = Stopwatch.createUnstarted();
    final long initialGas = gasLimit * 300;
    MessageFrame initialMessageFrame =
        MessageFrame.builder()
            .type(MessageFrame.Type.MESSAGE_CALL)
            .worldUpdater(worldUpdater.updater())
            .initialGas(initialGas)
            .contract(Address.ZERO)
            .address(RECEIVER)
            .originator(SENDER)
            .sender(SENDER)
            .gasPrice(Wei.ZERO)
            .inputData(
                Bytes.fromHexString(
                    "a9059cbb"
                        + "0000000000000000000000004bbeeb066ed09b7aed07bf39eee0460dfa261520"
                        + "00000000000000000000000000000000000000000000000002a34892d36d6c74"))
            .value(Wei.ZERO)
            .apparentValue(Wei.ZERO)
            .code(CodeFactory.createCode(codeBytes, 1, false))
            .blockValues(new SimpleBlockValues())
            .completer(c -> {})
            .miningBeneficiary(Address.ZERO)
            .blockHashLookup(h -> null)
            .build();
    final Deque<MessageFrame> messageFrameStack = initialMessageFrame.getMessageFrameStack();

    final MessageCallProcessor mcp = new MessageCallProcessor(evm, precompileContractRegistry);
    final ContractCreationProcessor ccp =
        new ContractCreationProcessor(londonGasCalculator, evm, true, List.of(), 0);
    stopwatch.start();
    OperationTracer tracer = OperationTracer.NO_TRACING;
    while (!messageFrameStack.isEmpty()) {
      MessageFrame messageFrame = messageFrameStack.peek();
      switch (messageFrame.getType()) {
        case MESSAGE_CALL -> mcp.process(messageFrame, tracer);
        case CONTRACT_CREATION -> ccp.process(messageFrame, tracer);
      }
    }
    stopwatch.stop();
    initialMessageFrame.getRevertReason().ifPresent(b -> System.out.println("Reverted - " + b));
    long gasUsed = initialGas - initialMessageFrame.getRemainingGas();
    long timeElapsedNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
    cumulativeGas += gasUsed;
    cumulativeNanos += timeElapsedNanos;
    if (verbose) {
      System.out.printf(
              OUT_FORMAT,
          getName().replace("__", "\t"),
          initialMessageFrame
              .getExceptionalHaltReason()
              .map(Object::toString)
              .orElse(initialMessageFrame.getState().toString()),
          gasUsed,
          timeElapsedNanos,
          gasUsed * 1_000_000_000.0 / timeElapsedNanos,
              yul.totalLoops(),
          timeElapsedNanos / yul.totalLoops(),
          initialMessageFrame.getRevertReason().orElse(Bytes.EMPTY).toUnprefixedHexString());
    }
  }

  public static void resetCumulative() {
    cumulativeGas = 0L;
    cumulativeNanos = 0L;
  }

  public static void reportHeader() {
    System.out.println(HEADER_OUT_FORMAT);
  }

  public static void reportCumulative() {
    System.out.printf(
            CUMULATIVE_OUT_FORMAT,
        "CUMULATIVE",
        "",
        cumulativeGas,
        cumulativeNanos,
        cumulativeGas * 1_000_000_000.0 / cumulativeNanos);
  }
}
