/**
 * Copyright (c) 2024 Glencoe Software, Inc. All rights reserved.
 *
 * This software is distributed under the terms described by the LICENSE.txt
 * file you can find at the root of the distribution bundle.  If the file is
 * missing please request a copy by contacting info@glencoesoftware.com
 */
package com.glencoesoftware.zarr;

// jzarr

import com.bc.zarr.ArrayParams;
import com.bc.zarr.DataType;
import com.bc.zarr.DimensionSeparator;
import com.bc.zarr.ZarrArray;
import com.bc.zarr.ZarrGroup;
import com.bc.zarr.storage.FileSystemStore;
import com.bc.zarr.ucar.NetCDF_Util;

// zarr-java

import dev.zarr.zarrjava.store.FilesystemStore;
import dev.zarr.zarrjava.store.Store;
import dev.zarr.zarrjava.store.StoreHandle;
import dev.zarr.zarrjava.utils.Utils;
import dev.zarr.zarrjava.v3.Array;
import dev.zarr.zarrjava.v3.Group;
import dev.zarr.zarrjava.v3.Node;
import dev.zarr.zarrjava.v3.codec.CodecBuilder;

// everything else

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Convert between v2 and v3 zarr.
 * jzarr is used to read/write v2, zarr-java is used to read/write v3.
 * Neither library has functioning support for more than one zarr version.
 */
public class Convert implements Callable<Integer> {

  private static final Logger LOGGER = LoggerFactory.getLogger(Convert.class);

  private static final String RESERVED_KEY = "zarr.json";

  private String inputLocation;
  private String outputLocation;
  private boolean writeV2;

  private ShardConfiguration shardConfig;
  private String[] codecs;

  /**
   * @param input path to the input data
   */
  @Parameters(
    index = "0",
    arity = "1",
    description = "file to convert"
  )
  public void setInput(String input) {
    inputLocation = input;
  }

  /**
   * @param output path to the output data
   */
  @Parameters(
    index = "1",
    arity = "1",
    description = "output location"
  )
  public void setOutput(String output) {
    outputLocation = output;
  }

  @Option(
    names = "--write-v2",
    description = "Read v3, write v2",
    defaultValue = "false"
  )
  public void setWriteV2(boolean v2) {
    writeV2 = v2;
  }

  @Option(
    names = "--shard",
    description = "'single' (one shard per array), " +
      "'chunk' (one chunk per shard), " +
      "'superchunk' (2x2 chunks per shard), " +
      "'t,c,z,y,x' (comma-separated custom shard size)",
    defaultValue = ""
  )
  public void setSharding(String shard) {
    if (shard != null && !shard.isEmpty()) {
      try {
        shardConfig = Enum.valueOf(ShardConfiguration.class, shard);
      }
      catch (IllegalArgumentException e) {
        // TODO
        shardConfig = ShardConfiguration.CUSTOM;
      }
    }
  }

  @Option(
    names = "--compression",
    split = ",",
    description = "Comma-separated codecs to apply. Options are " +
      "'gzip', 'zstd', 'blosc', 'crc32'",
    defaultValue = ""
  )
  public void setCompression(String[] compression) {
    if (compression != null) {
      codecs = compression;
    }
  }

  @Override
  public Integer call() throws Exception {
    if (writeV2) {
      convertToV2();
    }
    else {
      convertToV3();
    }

    return 0;
  }

  /**
   * Read v2 input data with jzarr, and write to v3 using zarr-java.
   */
  public void convertToV3() throws Exception {
    Path inputPath = Paths.get(inputLocation);

    // get the root-level attributes
    ZarrGroup reader = ZarrGroup.open(inputPath);
    Map<String, Object> attributes = reader.getAttributes();

    Set<String> groupKeys = reader.getGroupKeys();
    groupKeys.remove("OME");

    ZarrGroup omeGroup = ZarrGroup.open(inputPath.resolve("OME"));

    // Group.create(...) can accept a Map of attributes,
    // but this doesn't seem to actually create the group
    // separating the group creation and attribute writing into
    // two calls seems to work correctly
    FilesystemStore outputStore = new FilesystemStore(outputLocation);
    Group outputRootGroup = Group.create(outputStore.resolve());
    outputRootGroup.setAttributes(attributes);

    // copy OME-XML file
    Group outputOMEGroup = Group.create(outputStore.resolve("OME"));
    outputOMEGroup.setAttributes(omeGroup.getAttributes());
    Files.copy(Paths.get(inputLocation, "OME", "METADATA.ome.xml"),
      Paths.get(outputLocation, "OME", "METADATA.ome.xml"));

    for (String seriesGroupKey : groupKeys) {
      if (seriesGroupKey.indexOf("/") > 0) {
        continue;
      }
      Path seriesPath = inputPath.resolve(seriesGroupKey);
      ZarrGroup seriesGroup = ZarrGroup.open(seriesPath);
      LOGGER.info("opened {}", seriesPath);

      Map<String, Object> seriesAttributes = seriesGroup.getAttributes();
      LOGGER.info("got {} series attributes", seriesAttributes.size());

      Group outputSeriesGroup = Group.create(outputStore.resolve(seriesGroupKey));
      outputSeriesGroup.setAttributes(seriesAttributes);

      Set<String> columnKeys = seriesGroup.getGroupKeys();
      // "pass through" if this is not HCS
      if (columnKeys.size() == 0) {
        columnKeys.add("");
      }
      for (String columnKey : columnKeys) {
        if (columnKey.indexOf("/") > 0) {
          continue;
        }
        Path columnPath = columnKey.isEmpty() ? seriesPath : seriesPath.resolve(columnKey);
        ZarrGroup column = ZarrGroup.open(columnPath);

        if (!columnKey.isEmpty()) {
          Map<String, Object> columnAttributes = column.getAttributes();
          Group outputColumnGroup = Group.create(outputStore.resolve(seriesGroupKey, columnKey));
          outputColumnGroup.setAttributes(columnAttributes);
        }

        Set<String> fieldKeys = column.getGroupKeys();
        // "pass through" if this is not HCS
        if (fieldKeys.size() == 0) {
          fieldKeys.add("");
        }

        for (String fieldKey : fieldKeys) {
          Path fieldPath = fieldKey.isEmpty() ? columnPath : columnPath.resolve(fieldKey);
          ZarrGroup field = ZarrGroup.open(fieldPath);


          Map<String, Object> fieldAttributes = field.getAttributes();
          if (!fieldKey.isEmpty()) {
            Group outputFieldGroup = Group.create(outputStore.resolve(seriesGroupKey, columnKey, fieldKey));
            outputFieldGroup.setAttributes(fieldAttributes);
          }

          // calculate the number of resolutions
          int totalResolutions = 1;

          List<Map<String, Object>> multiscales =
            (List<Map<String, Object>>) fieldAttributes.get("multiscales");
          if (multiscales != null && multiscales.size() > 0) {
            List<Map<String, Object>> datasets =
              (List<Map<String, Object>>) multiscales.get(0).get("datasets");
            if (datasets != null) {
              totalResolutions = datasets.size();
            }
          }

          LOGGER.info("found {} resolutions", totalResolutions);

          for (int res=0; res<totalResolutions; res++) {
            String resolutionPath = fieldPath + "/" + res;

            ZarrArray tile = field.openArray("/" + res);
            LOGGER.info("opened array {}", resolutionPath);
            int[] chunkSizes = tile.getChunks();
            int[] shape = tile.getShape();

            int[] gridPosition = new int[] {0, 0, 0, 0, 0};
            int tileX = chunkSizes[chunkSizes.length - 2];
            int tileY = chunkSizes[chunkSizes.length - 1];

            DataType type = tile.getDataType();

            // create the v3 array for writing

            CodecBuilder codecBuilder = new CodecBuilder(getV3Type(type));
            if (shardConfig != null) {
              switch (shardConfig) {
                case SINGLE:
                  codecBuilder = codecBuilder.withSharding(shape);
                  break;
                case CHUNK:
                  codecBuilder = codecBuilder.withSharding(chunkSizes);
                  break;
                case SUPERCHUNK:
                  int[] shardSize = new int[chunkSizes.length];
                  System.arraycopy(chunkSizes, 0, shardSize, 0, shardSize.length);
                  shardSize[4] *= 2;
                  shardSize[3] *= 2;
                  codecBuilder = codecBuilder.withSharding(shardSize);
                  break;
                case CUSTOM:
                  // TODO
                  break;
              }
            }
            if (codecs != null) {
              for (String codecName : codecs) {
                if (codecName.equals("crc32")) {
                  codecBuilder = codecBuilder.withCrc32c();
                }
                else if (codecName.equals("zstd")) {
                  codecBuilder = codecBuilder.withZstd();
                }
                else if (codecName.equals("gzip")) {
                  codecBuilder = codecBuilder.withGzip();
                }
                else if (codecName.equals("blosc")) {
                  codecBuilder = codecBuilder.withBlosc();
                }
              }
            }
            final CodecBuilder builder = codecBuilder;

            Array outputArray = Array.create(outputStore.resolve(seriesGroupKey, columnKey, fieldKey, String.valueOf(res)),
              Array.metadataBuilder()
                .withShape(Utils.toLongArray(shape))
                .withDataType(getV3Type(type))
                .withChunkShape(chunkSizes)
                .withFillValue(255)
                .withCodecs(c -> builder)
                .build()
            );

            for (int t=0; t<shape[0]; t+=chunkSizes[0]) {
              for (int c=0; c<shape[1]; c+=chunkSizes[1]) {
                for (int z=0; z<shape[2]; z+=chunkSizes[2]) {
                  // copy each chunk, keeping the original chunk sizes
                  for (int y=0; y<shape[4]; y+=tileY) {
                    for (int x=0; x<shape[3]; x+=tileX) {
                      gridPosition[4] = y;
                      gridPosition[3] = x;
                      gridPosition[2] = z;
                      gridPosition[1] = c;
                      gridPosition[0] = t;
                      Object bytes = tile.read(chunkSizes, gridPosition);
                      outputArray.write(Utils.toLongArray(gridPosition), NetCDF_Util.createArrayWithGivenStorage(bytes, chunkSizes));
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  public void simpleCase() throws Exception {
    Store store = new FilesystemStore(inputLocation);
    Group firstSeries = Group.open(store.resolve("0"));

    Node[] resolutions = firstSeries.listAsArray();
    for (Node r : resolutions) {
      Array resolutionArray = (Array) r;

      long[] shape = resolutionArray.metadata.shape;
      int[] chunks = resolutionArray.metadata.chunkShape();

      int tileX = chunks[chunks.length - 2];
      int tileY = chunks[chunks.length - 1];

      long[] offset = new long[] {0, 0, 0, 0, 0};
      ucar.ma2.Array tile = resolutionArray.read(offset, chunks);

      // test dataset is uint8
      ByteBuffer buffer = tile.getDataAsByteBuffer();
      LOGGER.debug("comparing {} bytes against fill value", buffer.limit());
      int nonFill = 0;
      for (int i=0; i<buffer.limit(); i++) {
        if (buffer.get(i) != ((Number) resolutionArray.metadata.fillValue).byteValue()) {
          nonFill++;
        }
      }
      LOGGER.debug("bytes of non-fill: {}", nonFill);
    }
  }

  /**
   * Read v2 input data with jzarr, and write to v3 using zarr-java.
   */
  public void convertToV2() throws Exception {
    Store store = new FilesystemStore(inputLocation);

    Group v3Root = Group.open(store.resolve());
    Group ome = Group.open(store.resolve("OME"));

    ZarrGroup root = ZarrGroup.create(outputLocation);
    root.writeAttributes(v3Root.metadata.attributes);

    ZarrGroup omeGroup = ZarrGroup.create(Paths.get(outputLocation, "OME"));
    omeGroup.writeAttributes(ome.metadata.attributes);
    Files.copy(Paths.get(inputLocation, "OME", "METADATA.ome.xml"),
      Paths.get(outputLocation, "OME", "METADATA.ome.xml"));

    String[] seriesGroupKeys = v3Root.storeHandle.list().toArray(String[]::new);
    for (String seriesGroupKey : seriesGroupKeys) {
      if (seriesGroupKey.equals("OME") || seriesGroupKey.equals(RESERVED_KEY)) {
        continue;
      }

      Group firstSeries = Group.open(store.resolve(seriesGroupKey));

      ZarrGroup seriesGroup = ZarrGroup.create(Paths.get(outputLocation, seriesGroupKey));
      seriesGroup.writeAttributes(firstSeries.metadata.attributes);

      String[] columnKeys = firstSeries.storeHandle.list().toArray(String[]::new);
      if (columnKeys.length == 0) {
        columnKeys = new String[] {""};
      }
      for (String columnKey : columnKeys) {
        if (columnKey.equals(RESERVED_KEY)) {
          continue;
        }
        Node column = firstSeries.get(columnKey);
        if (!columnKey.isEmpty() && column instanceof Group) {
          ZarrGroup columnGroup = ZarrGroup.create(Paths.get(outputLocation, seriesGroupKey, columnKey));
          columnGroup.writeAttributes(((Group) column).metadata.attributes);
        }
        else {
          column = firstSeries;
        }

        String[] fieldKeys = ((Group) column).storeHandle.list().toArray(String[]::new);
        if (fieldKeys.length == 0) {
          fieldKeys = new String[] {""};
        }
        for (String fieldKey : fieldKeys) {
          if (fieldKey.equals(RESERVED_KEY)) {
            continue;
          }
          Node f = ((Group) column).get(fieldKey);
          if (!fieldKey.isEmpty() && f instanceof Group) {
            ZarrGroup fieldGroup = ZarrGroup.create(Paths.get(outputLocation, seriesGroupKey, columnKey, fieldKey));
            fieldGroup.writeAttributes(((Group) f).metadata.attributes);
          }
          else {
            f = column;
          }

          Node[] resolutions = ((Group) f).listAsArray();
          for (Node r : resolutions) {
            Array resolutionArray = (Array) r;

            long[] shape = resolutionArray.metadata.shape;
            int[] chunks = resolutionArray.metadata.chunkShape();

            int tileX = chunks[chunks.length - 2];
            int tileY = chunks[chunks.length - 1];

            long[] offset = new long[] {0, 0, 0, 0, 0};

            ArrayParams arrayParams = new ArrayParams()
              .shape(Utils.toIntArray(shape))
              .chunks(chunks)
              .dataType(getV2Type(resolutionArray.metadata.dataType))
              .dimensionSeparator(DimensionSeparator.SLASH);

            // "/" is intentional
            // see https://github.com/zarr-developers/zarr-java/blob/main/src/main/java/dev/zarr/zarrjava/store/StoreHandle.java#L67
            // there should be an easier way to do this
            String[] relativeArrayPath = r.storeHandle.toString().replace(store.toString(), "").split("/");
            Path outputArrayPath = Paths.get(outputLocation, relativeArrayPath);
            ZarrArray outputArray = ZarrArray.create(outputArrayPath, arrayParams);

            for (int t=0; t<shape[0]; t+=chunks[0]) {
              for (int c=0; c<shape[1]; c+=chunks[1]) {
                for (int z=0; z<shape[2]; z+=chunks[2]) {
                  for (int y=0; y<shape[4]; y+=tileY) {
                    for (int x=0; x<shape[3]; x+=tileX) {
                      offset[4] = y;
                      offset[3] = x;
                      offset[2] = z;
                      offset[1] = c;
                      offset[0] = t;

                      ucar.ma2.Array tile = resolutionArray.read(
                        offset,
                        chunks
                      );

                      // this call to tile.get1DJavaArray() is kind of silly
                      // jzarr will create a ucar.ma2.Array internally,
                      // but can't just accept an Array for writing
                      // zarr-java will only provide an Array
                      outputArray.write(tile.get1DJavaArray(resolutionArray.metadata.dataType.getMA2DataType()),
                        chunks, Utils.toIntArray(offset));
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * Convert jzarr type to zarr-java v3 type.
   */
  private dev.zarr.zarrjava.v3.DataType getV3Type(DataType v2) {
    switch (v2) {
      case f8:
        return dev.zarr.zarrjava.v3.DataType.FLOAT64;
      case f4:
        return dev.zarr.zarrjava.v3.DataType.FLOAT32;
      case i8:
        return dev.zarr.zarrjava.v3.DataType.INT64;
      case i4:
        return dev.zarr.zarrjava.v3.DataType.INT32;
      case u4:
        return dev.zarr.zarrjava.v3.DataType.UINT32;
      case i2:
        return dev.zarr.zarrjava.v3.DataType.INT16;
      case u2:
        return dev.zarr.zarrjava.v3.DataType.UINT16;
      case i1:
        return dev.zarr.zarrjava.v3.DataType.INT8;
      case u1:
        return dev.zarr.zarrjava.v3.DataType.UINT8;
    }
    throw new IllegalArgumentException(v2.toString());
  }

  /**
   * Convert zarr-java v3 type to jzarr type.
   */
  private DataType getV2Type(dev.zarr.zarrjava.v3.DataType v3) {
    switch (v3) {
      case FLOAT64:
        return DataType.f8;
      case FLOAT32:
        return DataType.f4;
      case INT64:
        return DataType.i8;
      case INT32:
        return DataType.i4;
      case UINT32:
        return DataType.u4;
      case INT16:
        return DataType.i2;
      case UINT16:
        return DataType.u2;
      case INT8:
        return DataType.i1;
      case UINT8:
        return DataType.u1;
    }
    throw new IllegalArgumentException(v3.toString());
  }

  public static void main(String[] args) {
    CommandLine.call(new Convert(), args);
  }

}
