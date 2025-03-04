/*
 * Copyright (C) 2018 The Android Open Source Project
 *
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
 * limitations under the License
 */
package com.android.tools.build.bundletool.io;

import static com.android.tools.build.bundletool.commands.BuildApksCommand.ApkBuildMode.SYSTEM;
import static com.android.tools.build.bundletool.io.ConcurrencyUtils.waitForAll;
import static com.android.tools.build.bundletool.model.utils.CollectorUtils.groupingByDeterministic;
import static com.android.tools.build.bundletool.model.utils.CollectorUtils.groupingBySortedKeys;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Predicates.alwaysTrue;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.mapping;

import com.android.bundle.Commands.ApkDescription;
import com.android.bundle.Commands.ApkSet;
import com.android.bundle.Commands.AssetModuleMetadata;
import com.android.bundle.Commands.AssetModulesInfo;
import com.android.bundle.Commands.AssetSliceSet;
import com.android.bundle.Commands.BuildApksResult;
import com.android.bundle.Commands.DefaultTargetingValue;
import com.android.bundle.Commands.DeliveryType;
import com.android.bundle.Commands.InstantMetadata;
import com.android.bundle.Commands.LocalTestingInfo;
import com.android.bundle.Commands.PermanentlyFusedModule;
import com.android.bundle.Commands.Variant;
import com.android.bundle.Config.AssetModulesConfig;
import com.android.bundle.Config.BundleConfig;
import com.android.bundle.Config.Bundletool;
import com.android.bundle.Config.SplitDimension;
import com.android.bundle.Config.SuffixStripping;
import com.android.bundle.Devices.DeviceSpec;
import com.android.bundle.Targeting.VariantTargeting;
import com.android.tools.build.bundletool.commands.BuildApksCommand.ApkBuildMode;
import com.android.tools.build.bundletool.commands.BuildApksModule.FirstVariantNumber;
import com.android.tools.build.bundletool.commands.BuildApksModule.VerboseLogs;
import com.android.tools.build.bundletool.device.ApkMatcher;
import com.android.tools.build.bundletool.io.ApkSetBuilderFactory.ApkSetBuilder;
import com.android.tools.build.bundletool.model.AndroidManifest;
import com.android.tools.build.bundletool.model.ApkListener;
import com.android.tools.build.bundletool.model.ApkModifier;
import com.android.tools.build.bundletool.model.ApkModifier.ApkDescription.ApkType;
import com.android.tools.build.bundletool.model.AppBundle;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.model.BundleModuleName;
import com.android.tools.build.bundletool.model.GeneratedApks;
import com.android.tools.build.bundletool.model.GeneratedAssetSlices;
import com.android.tools.build.bundletool.model.ManifestDeliveryElement;
import com.android.tools.build.bundletool.model.ModuleSplit;
import com.android.tools.build.bundletool.model.ModuleSplit.SplitType;
import com.android.tools.build.bundletool.model.OptimizationDimension;
import com.android.tools.build.bundletool.model.VariantKey;
import com.android.tools.build.bundletool.model.ZipPath;
import com.android.tools.build.bundletool.model.version.BundleToolVersion;
import com.android.tools.build.bundletool.optimizations.ApkOptimizations;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.protobuf.Int32Value;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.inject.Inject;

/** Creates parts of table of contents and writes out APKs. */
public class ApkSerializerManager {

  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final AppBundle appBundle;
  private final ApkListener apkListener;
  private final ApkModifier apkModifier;
  private final ListeningExecutorService executorService;
  private final int firstVariantNumber;
  private final boolean verbose;

  private final ApkPathManager apkPathManager;
  private final ApkOptimizations apkOptimizations;

  @Inject
  public ApkSerializerManager(
      AppBundle appBundle,
      Optional<ApkListener> apkListener,
      Optional<ApkModifier> apkModifier,
      ListeningExecutorService executorService,
      @FirstVariantNumber Optional<Integer> firstVariantNumber,
      @VerboseLogs boolean verbose,
      ApkPathManager apkPathManager,
      ApkOptimizations apkOptimizations) {
    this.appBundle = appBundle;
    this.apkListener = apkListener.orElse(ApkListener.NO_OP);
    this.apkModifier = apkModifier.orElse(ApkModifier.NO_OP);
    this.executorService = executorService;
    this.firstVariantNumber = firstVariantNumber.orElse(0);
    this.verbose = verbose;
    this.apkPathManager = apkPathManager;
    this.apkOptimizations = apkOptimizations;
  }

  public void populateApkSetBuilder(
      ApkSetBuilder apkSetBuilder,
      GeneratedApks generatedApks,
      GeneratedAssetSlices generatedAssetSlices,
      ApkBuildMode apkBuildMode,
      Optional<DeviceSpec> deviceSpec,
      LocalTestingInfo localTestingInfo,
      ImmutableSet<BundleModuleName> permanentlyFusedModules) {
    ImmutableList<Variant> allVariantsWithTargeting =
        serializeApks(apkSetBuilder, generatedApks, apkBuildMode, deviceSpec);
    ImmutableList<AssetSliceSet> allAssetSliceSets =
        serializeAssetSlices(apkSetBuilder, generatedAssetSlices, apkBuildMode, deviceSpec);
    // Finalize the output archive.
    BuildApksResult.Builder apksResult =
        BuildApksResult.newBuilder()
            .setPackageName(appBundle.getPackageName())
            .addAllVariant(allVariantsWithTargeting)
            .setBundletool(
                Bundletool.newBuilder()
                    .setVersion(BundleToolVersion.getCurrentVersion().toString()))
            .addAllAssetSliceSet(allAssetSliceSets)
            .setLocalTestingInfo(localTestingInfo);
    if (appBundle.getBundleConfig().hasAssetModulesConfig()) {
      apksResult.setAssetModulesInfo(
          getAssetModulesInfo(appBundle.getBundleConfig().getAssetModulesConfig()));
    }
    apksResult.addAllDefaultTargetingValue(getDefaultTargetingValues(appBundle.getBundleConfig()));
    permanentlyFusedModules.forEach(
        moduleName ->
            apksResult.addPermanentlyFusedModules(
                PermanentlyFusedModule.newBuilder().setName(moduleName.getName())));
    apkSetBuilder.setTableOfContentsFile(apksResult.build());
  }

  @VisibleForTesting
  ImmutableList<Variant> serializeApksForDevice(
      ApkSetBuilder apkSetBuilder,
      GeneratedApks generatedApks,
      DeviceSpec deviceSpec,
      ApkBuildMode apkBuildMode) {
    return serializeApks(apkSetBuilder, generatedApks, apkBuildMode, Optional.of(deviceSpec));
  }

  @VisibleForTesting
  ImmutableList<Variant> serializeApks(ApkSetBuilder apkSetBuilder, GeneratedApks generatedApks) {
    return serializeApks(apkSetBuilder, generatedApks, ApkBuildMode.DEFAULT);
  }

  @VisibleForTesting
  ImmutableList<Variant> serializeApks(
      ApkSetBuilder apkSetBuilder, GeneratedApks generatedApks, ApkBuildMode apkBuildMode) {
    return serializeApks(apkSetBuilder, generatedApks, apkBuildMode, Optional.empty());
  }

  private ImmutableList<Variant> serializeApks(
      ApkSetBuilder apkSetBuilder,
      GeneratedApks generatedApks,
      ApkBuildMode apkBuildMode,
      Optional<DeviceSpec> deviceSpec) {
    validateInput(generatedApks, apkBuildMode);

    // Running with system APK mode generates a fused APK and additional unmatched language splits.
    // To avoid filtering of unmatched language splits we skip device filtering for system mode.
    Predicate<ModuleSplit> deviceFilter =
        deviceSpec.isPresent() && !apkBuildMode.equals(SYSTEM)
            ? new ApkMatcher(addDefaultDeviceTierIfNecessary(deviceSpec.get()))
                ::matchesModuleSplitByTargeting
            : alwaysTrue();

    ImmutableListMultimap<VariantKey, ModuleSplit> splitsByVariant =
        generatedApks.getAllApksGroupedByOrderedVariants();

    // Assign the variant numbers to each variant present.
    AtomicInteger variantNumberCounter = new AtomicInteger(firstVariantNumber);
    ImmutableMap<VariantKey, Integer> variantNumberByVariantKey =
        splitsByVariant.keySet().stream()
            .collect(toImmutableMap(identity(), unused -> variantNumberCounter.getAndIncrement()));

    // 1. Remove APKs not matching the device spec.
    // 2. Modify the APKs based on the ApkModifier.
    // 3. Serialize all APKs in parallel.
    ApkSerializer apkSerializer = new ApkSerializer(apkListener, apkBuildMode);

    // Modifies the APK using APK modifier, then returns a map by extracting the variant
    // of APK first and later clearing out its variant targeting.

    ImmutableListMultimap<VariantKey, ModuleSplit> finalSplitsByVariant =
        splitsByVariant.entries().stream()
            .filter(keyModuleSplitEntry -> deviceFilter.test(keyModuleSplitEntry.getValue()))
            .collect(
                groupingBySortedKeys(
                    Entry::getKey,
                    entry ->
                        clearVariantTargeting(
                            modifyApk(
                                entry.getValue(), variantNumberByVariantKey.get(entry.getKey())))));

    // After variant targeting of APKs are cleared, there might be duplicate APKs
    // which are removed and the distinct APKs are then serialized in parallel.
    ImmutableMap<ModuleSplit, ApkDescription> apkDescriptionBySplit =
        finalSplitsByVariant.values().stream()
            .distinct()
            .collect(
                collectingAndThen(
                    toImmutableMap(
                        identity(),
                        (ModuleSplit split) -> {
                          ZipPath apkPath = apkPathManager.getApkPath(split);
                          return executorService.submit(
                              () -> apkSerializer.serialize(apkSetBuilder, split, apkPath));
                        }),
                    ConcurrencyUtils::waitForAll));

    // Build the result proto.
    ImmutableList.Builder<Variant> variants = ImmutableList.builder();
    for (VariantKey variantKey : finalSplitsByVariant.keySet()) {
      Variant.Builder variant =
          Variant.newBuilder()
              .setVariantNumber(variantNumberByVariantKey.get(variantKey))
              .setTargeting(variantKey.getVariantTargeting());

      Multimap<BundleModuleName, ModuleSplit> splitsByModuleName =
          finalSplitsByVariant.get(variantKey).stream()
              .collect(groupingBySortedKeys(ModuleSplit::getModuleName));

      for (BundleModuleName moduleName : splitsByModuleName.keySet()) {
        variant.addApkSet(
            ApkSet.newBuilder()
                .setModuleMetadata(appBundle.getModule(moduleName).getModuleMetadata())
                .addAllApkDescription(
                    splitsByModuleName.get(moduleName).stream()
                        .map(apkDescriptionBySplit::get)
                        .collect(toImmutableList())));
      }
      variants.add(variant.build());
    }

    return variants.build();
  }

  @VisibleForTesting
  ImmutableList<AssetSliceSet> serializeAssetSlices(
      ApkSetBuilder apkSetBuilder,
      GeneratedAssetSlices generatedAssetSlices,
      ApkBuildMode apkBuildMode,
      Optional<DeviceSpec> deviceSpec) {

    Predicate<ModuleSplit> deviceFilter =
        deviceSpec.isPresent()
            ? new ApkMatcher(addDefaultDeviceTierIfNecessary(deviceSpec.get()))
                ::matchesModuleSplitByTargeting
            : alwaysTrue();

    ApkSerializer apkSerializer = new ApkSerializer(apkListener, apkBuildMode);

    ImmutableListMultimap<BundleModuleName, ApkDescription> generatedSlicesByModule =
        generatedAssetSlices.getAssetSlices().stream()
            .filter(deviceFilter)
            .collect(
                groupingByDeterministic(
                    ModuleSplit::getModuleName,
                    mapping(
                        assetSlice -> {
                          ZipPath apkPath = apkPathManager.getApkPath(assetSlice);
                          return executorService.submit(
                              () -> apkSerializer.serialize(apkSetBuilder, assetSlice, apkPath));
                        },
                        toImmutableList())))
            .entrySet()
            .stream()
            .collect(
                ImmutableListMultimap.flatteningToImmutableListMultimap(
                    Entry::getKey, entry -> waitForAll(entry.getValue()).stream()));
    return generatedSlicesByModule.asMap().entrySet().stream()
        .map(
            entry ->
                AssetSliceSet.newBuilder()
                    .setAssetModuleMetadata(
                        getAssetModuleMetadata(appBundle.getModule(entry.getKey())))
                    .addAllApkDescription(entry.getValue())
                    .build())
        .collect(toImmutableList());
  }

  private AssetModuleMetadata getAssetModuleMetadata(BundleModule module) {
    AndroidManifest manifest = module.getAndroidManifest();
    AssetModuleMetadata.Builder metadataBuilder =
        AssetModuleMetadata.newBuilder().setName(module.getName().getName());
    Optional<ManifestDeliveryElement> persistentDelivery = manifest.getManifestDeliveryElement();
    metadataBuilder.setDeliveryType(
        persistentDelivery
            .map(delivery -> getDeliveryType(delivery))
            .orElse(DeliveryType.INSTALL_TIME));
    // The module is instant if either the dist:instant attribute is true or the
    // dist:instant-delivery element is present.
    boolean isInstantModule = module.isInstantModule();
    InstantMetadata.Builder instantMetadataBuilder =
        InstantMetadata.newBuilder().setIsInstant(isInstantModule);
    // The ManifestDeliveryElement is present if the dist:instant-delivery element is used.
    Optional<ManifestDeliveryElement> instantDelivery =
        manifest.getInstantManifestDeliveryElement();
    if (isInstantModule) {
      // If it's an instant-enabled module, the instant delivery is on-demand if the dist:instant
      // attribute was set to true or if the dist:instant-delivery element was used without an
      // install-time element.
      instantMetadataBuilder.setDeliveryType(
          instantDelivery
              .map(delivery -> getDeliveryType(delivery))
              .orElse(DeliveryType.ON_DEMAND));
    }
    metadataBuilder.setInstantMetadata(instantMetadataBuilder.build());
    return metadataBuilder.build();
  }

  private static void validateInput(GeneratedApks generatedApks, ApkBuildMode apkBuildMode) {
    switch (apkBuildMode) {
      case DEFAULT:
        checkArgument(
            generatedApks.getSystemApks().isEmpty(),
            "Internal error: System APKs can only be set in system mode.");
        break;
      case UNIVERSAL:
        checkArgument(
            generatedApks.getSplitApks().isEmpty()
                && generatedApks.getInstantApks().isEmpty()
                && generatedApks.getHibernatedApks().isEmpty()
                && generatedApks.getSystemApks().isEmpty(),
            "Internal error: For universal APK expecting only standalone APKs.");
        break;
      case SYSTEM:
        checkArgument(
            generatedApks.getSplitApks().isEmpty()
                && generatedApks.getInstantApks().isEmpty()
                && generatedApks.getHibernatedApks().isEmpty()
                && generatedApks.getStandaloneApks().isEmpty(),
            "Internal error: For system mode expecting only system APKs.");
        break;
      case PERSISTENT:
        checkArgument(
            generatedApks.getSystemApks().isEmpty(),
            "Internal error: System APKs not expected with persistent mode.");
        checkArgument(
            generatedApks.getInstantApks().isEmpty(),
            "Internal error: Instant APKs not expected with persistent mode.");
        break;
      case INSTANT:
        checkArgument(
            generatedApks.getSystemApks().isEmpty(),
            "Internal error: System APKs not expected with instant mode.");
        checkArgument(
            generatedApks.getSplitApks().isEmpty() && generatedApks.getStandaloneApks().isEmpty(),
            "Internal error: Persistent APKs not expected with instant mode.");
        break;
      case ARCHIVE:
        checkArgument(
            generatedApks.getSplitApks().isEmpty()
                && generatedApks.getInstantApks().isEmpty()
                && generatedApks.getStandaloneApks().isEmpty()
                && generatedApks.getSystemApks().isEmpty(),
            "Internal error: For hibernation mode expecting only hibernated APKs.");
        break;
    }
  }

  private ModuleSplit modifyApk(ModuleSplit moduleSplit, int variantNumber) {
    ApkModifier.ApkDescription apkDescription =
        ApkModifier.ApkDescription.builder()
            .setBase(moduleSplit.isBaseModuleSplit())
            .setApkType(
                moduleSplit.getSplitType().equals(SplitType.STANDALONE)
                    ? ApkType.STANDALONE
                    : (moduleSplit.isMasterSplit() ? ApkType.MASTER_SPLIT : ApkType.CONFIG_SPLIT))
            .setVariantNumber(variantNumber)
            .setVariantTargeting(moduleSplit.getVariantTargeting())
            .setApkTargeting(moduleSplit.getApkTargeting())
            .build();

    return moduleSplit.toBuilder()
        .setAndroidManifest(
            apkModifier.modifyManifest(moduleSplit.getAndroidManifest(), apkDescription))
        .build();
  }

  private static ModuleSplit clearVariantTargeting(ModuleSplit moduleSplit) {
    return moduleSplit.toBuilder()
        .setVariantTargeting(VariantTargeting.getDefaultInstance())
        .build();
  }

  private static AssetModulesInfo getAssetModulesInfo(AssetModulesConfig assetModulesConfig) {
    return AssetModulesInfo.newBuilder()
        .addAllAppVersion(assetModulesConfig.getAppVersionList())
        .setAssetVersionTag(assetModulesConfig.getAssetVersionTag())
        .build();
  }

  private static ImmutableList<DefaultTargetingValue> getDefaultTargetingValues(
      BundleConfig bundleConfig) {
    return bundleConfig.getOptimizations().getSplitsConfig().getSplitDimensionList().stream()
        .filter(SplitDimension::hasSuffixStripping)
        .map(
            splitDimension ->
                DefaultTargetingValue.newBuilder()
                    .setDimension(splitDimension.getValue())
                    .setDefaultValue(splitDimension.getSuffixStripping().getDefaultSuffix())
                    .build())
        .collect(toImmutableList());
  }

  private static DeliveryType getDeliveryType(ManifestDeliveryElement deliveryElement) {
    if (deliveryElement.hasOnDemandElement()) {
      return DeliveryType.ON_DEMAND;
    }
    if (deliveryElement.hasFastFollowElement()) {
      return DeliveryType.FAST_FOLLOW;
    }
    return DeliveryType.INSTALL_TIME;
  }

  /**
   * Adds a default device tier to the given {@link DeviceSpec} if it has none.
   *
   * <p>The default tier is taken from the optimization settings in the {@link
   * com.android.bundle.Config.BundleConfig}. If suffix stripping is enabled but the default tier is
   * unspecified, it defaults to 0.
   */
  private DeviceSpec addDefaultDeviceTierIfNecessary(DeviceSpec deviceSpec) {
    if (deviceSpec.hasDeviceTier()) {
      return deviceSpec;
    }
    Optional<SuffixStripping> deviceTierSuffix =
        Optional.ofNullable(
            apkOptimizations.getSuffixStrippings().get(OptimizationDimension.DEVICE_TIER));
    if (!deviceTierSuffix.isPresent()) {
      return deviceSpec;
    }
    return deviceSpec.toBuilder()
        .setDeviceTier(
            Int32Value.of(
                deviceTierSuffix
                    .map(
                        suffix ->
                            // Use the standard default value 0 if the app doesn't specify an
                            // explicit default.
                            suffix.getDefaultSuffix().isEmpty()
                                ? 0
                                : Integer.parseInt(suffix.getDefaultSuffix()))
                    .orElse(0)))
        .build();
  }

  private final class ApkSerializer {
    private final ApkListener apkListener;
    private final ApkBuildMode apkBuildMode;

    public ApkSerializer(ApkListener apkListener, ApkBuildMode apkBuildMode) {
      this.apkListener = apkListener;
      this.apkBuildMode = apkBuildMode;
    }

    public ApkDescription serialize(
        ApkSetBuilder apkSetBuilder, ModuleSplit split, ZipPath apkPath) {
      ApkDescription apkDescription;
      switch (split.getSplitType()) {
        case INSTANT:
          apkDescription = apkSetBuilder.addInstantApk(split, apkPath);
          break;
        case SPLIT:
          apkDescription = apkSetBuilder.addSplitApk(split, apkPath);
          break;
        case SYSTEM:
          if (split.isBaseModuleSplit() && split.isMasterSplit()) {
            apkDescription = apkSetBuilder.addSystemApk(split, apkPath);
          } else {
            apkDescription = apkSetBuilder.addSplitApk(split, apkPath);
          }
          break;
        case STANDALONE:
          apkDescription =
              apkBuildMode.equals(ApkBuildMode.UNIVERSAL)
                  ? apkSetBuilder.addStandaloneUniversalApk(split)
                  : apkSetBuilder.addStandaloneApk(split, apkPath);
          break;
        case ASSET_SLICE:
          apkDescription = apkSetBuilder.addAssetSliceApk(split, apkPath);
          break;
        case HIBERNATION:
          apkDescription = apkSetBuilder.addHibernatedApk(split, apkPath);
          break;
        default:
          throw new IllegalStateException("Unexpected splitType: " + split.getSplitType());
      }

      apkListener.onApkFinalized(apkDescription);

      if (verbose) {
        System.out.printf(
            "INFO: [%s] '%s' of type '%s' was written to disk.%n",
            LocalDateTime.now(ZoneId.systemDefault()).format(DATE_FORMATTER),
            apkPath,
            split.getSplitType());
      }

      return apkDescription;
    }
  }
}
