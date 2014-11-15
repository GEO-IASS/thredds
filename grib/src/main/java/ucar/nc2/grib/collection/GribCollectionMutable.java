/*
 *
 *  * Copyright 1998-2014 University Corporation for Atmospheric Research/Unidata
 *  *
 *  *  Portions of this software were developed by the Unidata Program at the
 *  *  University Corporation for Atmospheric Research.
 *  *
 *  *  Access and use of this software shall impose the following obligations
 *  *  and understandings on the user. The user is granted the right, without
 *  *  any fee or cost, to use, copy, modify, alter, enhance and distribute
 *  *  this software, and any derivative works thereof, and its supporting
 *  *  documentation for any purpose whatsoever, provided that this entire
 *  *  notice appears in all copies of the software, derivative works and
 *  *  supporting documentation.  Further, UCAR requests that the user credit
 *  *  UCAR/Unidata in any publications that result from the use of this
 *  *  software or in any product that includes this software. The names UCAR
 *  *  and/or Unidata, however, may not be used in any advertising or publicity
 *  *  to endorse or promote any products or commercial entity unless specific
 *  *  written permission is obtained from UCAR/Unidata. The user also
 *  *  understands that UCAR/Unidata is not obligated to provide the user with
 *  *  any support, consulting, training or assistance of any kind with regard
 *  *  to the use, operation and performance of this software nor to provide
 *  *  the user with any updates, revisions, new versions or "bug fixes."
 *  *
 *  *  THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 *  *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  *  DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 *  *  INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 *  *  FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 *  *  NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 *  *  WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 *
 */

package ucar.nc2.grib.collection;

import net.jcip.annotations.Immutable;
import thredds.featurecollection.FeatureCollectionConfig;
import thredds.inventory.CollectionAbstract;
import thredds.inventory.MFile;
import ucar.coord.*;
import ucar.nc2.grib.grib1.Grib1ParamTime;
import ucar.nc2.grib.grib1.Grib1SectionProductDefinition;
import ucar.nc2.grib.grib1.tables.Grib1Customizer;
import ucar.nc2.grib.grib2.Grib2SectionProductDefinition;
import ucar.nc2.time.CalendarDate;
import ucar.nc2.time.CalendarDateFormatter;
import ucar.nc2.time.CalendarTimeZone;
import ucar.nc2.grib.*;
import ucar.nc2.grib.grib2.Grib2Pds;
import ucar.nc2.grib.grib2.Grib2Utils;
import ucar.nc2.time.CalendarDateRange;
import ucar.nc2.util.cache.SmartArrayInt;
import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.util.Parameter;
import ucar.unidata.util.StringUtil2;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * A mutable class for building.
 *
 * @author John
 * @since 12/1/13
 */
public class GribCollectionMutable implements AutoCloseable {
  static private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(GribCollectionMutable.class);
  static public  final long MISSING_RECORD = -1;

  //////////////////////////////////////////////////////////

  static MFile makeIndexMFile(String collectionName, File directory) {
    String nameNoBlanks = StringUtil2.replace(collectionName, ' ', "_");
    return new GcMFile(directory, nameNoBlanks + CollectionAbstract.NCX_SUFFIX, -1, -1, -1); // LOOK dont know lastMod, size. can it be added later?
  }

  private static CalendarDateFormatter cf = new CalendarDateFormatter("yyyyMMdd-HHmmss", new CalendarTimeZone("UTC"));

  static public String makeName(String collectionName, CalendarDate runtime) {
    String nameNoBlanks = StringUtil2.replace(collectionName, ' ', "_");
    return nameNoBlanks + "-" + cf.toString(runtime);
  }

  ////////////////////////////////////////////////////////////////
  protected String name; // collection name; index filename must be directory/name.ncx2
  protected FeatureCollectionConfig config;
  protected boolean isGrib1;
  protected File directory;
  protected String orgDirectory;

  // set by the builder
  public int version; // the ncx version
  public int center, subcenter, master, local;  // GRIB 1 uses "local" for table version
  public int genProcessType, genProcessId, backProcessId;
  public List<Parameter> params;          // not used
  protected Map<Integer, MFile> fileMap;    // all the files used in the GC; key in index in original collection, GC has subset of them
  protected List<Dataset> datasets;
  protected List<GribHorizCoordSystem> horizCS; // one for each unique GDS
  protected CoordinateRuntime masterRuntime;
  protected GribTables cust;

  // not stored in index
  protected RandomAccessFile indexRaf; // this is the raf of the index (ncx) file, synchronize any access to it
  protected String indexFilename;

  public static int countGC;

  protected GribCollectionMutable(String name, File directory, FeatureCollectionConfig config, boolean isGrib1) {
    countGC++;
    this.name = name;
    this.directory = directory;
    this.config = config;
    this.isGrib1 = isGrib1;
    if (config == null)
      logger.error("HEY GribCollection {} has empty config%n", name);
    if (name == null)
      logger.error("HEY GribCollection has null name dir={}%n", directory);
  }

  // for making partition collection
  protected void copyInfo(GribCollectionMutable from) {
    this.center = from.center;
    this.subcenter = from.subcenter;
    this.master = from.master;
    this.local = from.local;
    this.genProcessType = from.genProcessType;
    this.genProcessId = from.genProcessId;
    this.backProcessId = from.backProcessId;
  }

  public String getName() {
    return name;
  }

  public File getDirectory() {
    return directory;
  }

  public String getLocation() {
    if (indexRaf != null) return indexRaf.getLocation();
    return getIndexFilepathInCache();
  }

  public Collection<MFile> getFiles() {
    return fileMap.values();
  }

  public FeatureCollectionConfig getConfig() {
    return config;
  }

  /**
   * The files that comprise the collection.
   * Actual paths, including the grib cache if used.
   *
   * @return list of filename.
   */
  public List<String> getFilenames() {
    List<String> result = new ArrayList<>();
    for (MFile file : fileMap.values())
      result.add(file.getPath());
    Collections.sort(result);
    return result;
  }

  public File getIndexParentFile() {
    if (indexRaf == null) return null;
    Path index = Paths.get(indexRaf.getLocation());
    Path parent = index.getParent();
    return parent.toFile();
  }

  public String getFilename(int fileno) {
    return fileMap.get(fileno).getPath();
  }

  public List<Dataset> getDatasets() {
    return datasets;
  }

  public Dataset makeDataset(GribCollectionImmutable.Type type) {
    Dataset result = new Dataset(type);
    datasets.add(result);
    return result;
  }

  public GribCollectionMutable.Dataset getDatasetCanonical() {
    for (GribCollectionMutable.Dataset ds : datasets) {
      if (ds.type == GribCollectionImmutable.Type.GC) return ds;
      if (ds.type == GribCollectionImmutable.Type.TwoD) return ds;
    }
    throw new IllegalStateException("GC.getDatasetCanonical failed on="+name);
  }

  public GribHorizCoordSystem getHorizCS(int index) {
    return horizCS.get(index);
  }

  protected void makeHorizCS() {
    Map<Integer, GribHorizCoordSystem> gdsMap = new HashMap<>();
    for (Dataset ds : datasets) {
      for (GroupGC hcs : ds.groups)
        gdsMap.put(hcs.getGdsHash(), hcs.horizCoordSys);
    }

    horizCS = new ArrayList<>();
    for (GribHorizCoordSystem hcs : gdsMap.values())
      horizCS.add(hcs);
  }

  public int findHorizCS(GribHorizCoordSystem hcs) {
    return horizCS.indexOf(hcs);
  }

  public void addHorizCoordSystem(GdsHorizCoordSys hcs, byte[] rawGds, int gdsHash, int predefinedGridDefinition) {
                // GdsHorizCoordSys hcs, byte[] rawGds, int gdsHash, String id, String description, int predefinedGridDefinition
    horizCS.add(new GribHorizCoordSystem(hcs, rawGds, gdsHash, hcs.makeId(), hcs.makeDescription(), predefinedGridDefinition));
  }

  public void setFileMap(Map<Integer, MFile> fileMap) {
    this.fileMap = fileMap;
  }

  /**
   * public by accident, do not use
   *
   * @param indexRaf the open raf of the index file
   */
  void setIndexRaf(RandomAccessFile indexRaf) {
    this.indexRaf = indexRaf;
    if (indexRaf != null) {
      this.indexFilename = indexRaf.getLocation();
    }
  }

  /**
   * get index filename
   *
   * @return index filename; may not exist; may be in disk cache
   */
  public String getIndexFilepathInCache() {
    File indexFile = GribCdmIndex.makeIndexFile(name, directory);
    return GribCdmIndex.getFileInCache(indexFile.getPath()).getPath();
  }

  // set from GribCollectionBuilderFromIndex.readFromIndex()
  public File setOrgDirectory(String orgDirectory) {
    this.orgDirectory = orgDirectory;
    directory = new File(orgDirectory);
    if (!directory.exists())  {
      File indexFile = new File(indexFilename);
      File parent = indexFile.getParentFile();
      if (parent.exists())
        directory = parent;
    }
    return directory;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // stuff for Iosp

  public RandomAccessFile getDataRaf(int fileno) throws IOException {
    // absolute location
    MFile mfile = fileMap.get(fileno);
    String filename = mfile.getPath();
    File dataFile = new File(filename);

    // if data file does not exist, check reletive location - eg may be /upc/share instead of Q:
    if (!dataFile.exists() && indexRaf != null) {
      File index = new File(indexRaf.getLocation());
      File parent = index.getParentFile();
      if (fileMap.size() == 1) {
        dataFile = new File(parent, name); // single file case
      } else {
        dataFile = new File(parent, dataFile.getName()); // must be in same directory as the ncx file
      }
    }

    // data file not here
    if (!dataFile.exists()) {
      throw new FileNotFoundException("data file not found = " + dataFile.getPath());
    }

    RandomAccessFile want = RandomAccessFile.acquire(dataFile.getPath());
    want.order(RandomAccessFile.BIG_ENDIAN);
    return want;
  }

  // debugging
  public String getDataFilename(int fileno) throws IOException {
    // absolute location
    MFile mfile = fileMap.get(fileno);
    return mfile.getPath();
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // stuff for FileCacheable

  public void close() throws java.io.IOException {

    if (indexRaf != null) {
      indexRaf.close();
      indexRaf = null;
    }
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////////

  // these objects are created from the ncx index. lame - should only be in the builder i think
  private Set<String> groupNames = new HashSet<>(5);

  public class Dataset {
    final GribCollectionImmutable.Type type;
    List<GroupGC> groups;  // must be kept in order, because PartitionForVariable2D has index into it

    public Dataset(GribCollectionImmutable.Type type) {
      this.type = type;
      groups = new ArrayList<>();
    }

    Dataset(Dataset from) {
      this.type = from.type;
      groups = new ArrayList<>(from.groups.size());
    }

    public GroupGC addGroupCopy(GroupGC from) {
      GroupGC g = new GroupGC(from);
      groups.add(g);
      return g;
    }

    public boolean isTwoD() {
      return type == GribCollectionImmutable.Type.TwoD;
    }

    public GroupGC getGroup(int index) {
      return groups.get(index);
    }

    public GroupGC findGroupById(String id) {
      for (GroupGC g : groups) {
        if (g.getId().equals(id))
          return g;
      }
      return null;
    }

    public GribCollectionImmutable.Type getType() {
      return type;
    }

    public List<GroupGC> getGroups() {
      return groups;
    }
  }

 /*  @Immutable
  public class HorizCoordSys { // encapsolates the gds; shared by the GroupHcs
    private final GdsHorizCoordSys hcs;
    private final byte[] rawGds;
    private final int gdsHash;
    private final String id, description;
    private final String nameOverride;
    private final int predefinedGridDefinition;

    public HorizCoordSys(GdsHorizCoordSys hcs, byte[] rawGds, int gdsHash, String nameOverride, int predefinedGridDefinition) {
      this.hcs = hcs;
      this.rawGds = rawGds;
      this.gdsHash = gdsHash;
      this.nameOverride = nameOverride;
      this.predefinedGridDefinition = predefinedGridDefinition;

      this.id = makeId();
      this.description = makeDescription();
    }

    public GdsHorizCoordSys getHcs() {
      return hcs;
    }

    public byte[] getRawGds() {
      return rawGds;
    }

    public int getGdsHash() {
      return gdsHash;
    }

    // unique name for Group
    public String getId() {
      return id;
    }

    // human readable
    public String getDescription() {
      return description;
    }

    public String getNameOverride() {
      return nameOverride;
    }

    public int getPredefinedGridDefinition() {
      return predefinedGridDefinition;
    }

    private String makeId() {
      if (nameOverride != null) return nameOverride;

      // default id
      String base = hcs.makeId();
      // ensure uniqueness
      String tryit = base;
      int count = 1;
      while (groupNames.contains(tryit)) {
        count++;
        tryit = base + "-" + count;
      }
      groupNames.add(tryit);
      return tryit;
    }

    private String makeDescription() {
      // check for user defined group names
      String result = null;
      if (config.gribConfig.gdsNamer != null)
        result = config.gribConfig.gdsNamer.get(gdsHash);
      if (result != null) return result;

      return hcs.makeDescription(); // default desc
    }
  }  */

  // this class should be immutable, because it escapes
  public class GroupGC implements Comparable<GroupGC> {
    GribHorizCoordSystem horizCoordSys;
    List<VariableIndex> variList;
    List<Coordinate> coords;      // shared coordinates
    int[] filenose;               // key for GC.fileMap
    Map<Integer, GribCollectionMutable.VariableIndex> varMap;
    boolean isTwod = true;        // true for GC and twoD; so should be called "reference" dataset or something

    GroupGC() {
      this.variList = new ArrayList<>();
      this.coords = new ArrayList<>();
    }

    // copy constructor for PartitionBuilder
    GroupGC(GroupGC from) {
      this.horizCoordSys = from.horizCoordSys;     // reference
      this.variList = new ArrayList<>(from.variList.size());
      this.coords = new ArrayList<>(from.coords.size());
      this.isTwod = from.isTwod;
    }

    public void setHorizCoordSystem(GdsHorizCoordSys hcs, byte[] rawGds, int gdsHash, String nameOverride, int predefinedGridDefinition) {
           // check for user defined group names
      String desc = null;
      if (config.gribConfig.gdsNamer != null)
        desc = config.gribConfig.gdsNamer.get(gdsHash);
      if (desc == null) desc = hcs.makeDescription(); // default desc

                     // GdsHorizCoordSys hcs, byte[] rawGds, int gdsHash, String id, String description, int predefinedGridDefinition
      horizCoordSys = new GribHorizCoordSystem(hcs, rawGds, gdsHash, nameOverride, desc, predefinedGridDefinition);
    }

    public VariableIndex addVariable(VariableIndex vi) {
      variList.add(vi);
      return vi;
    }

    public GribCollectionMutable getGribCollection() {
      return GribCollectionMutable.this;
    }

    public Iterable<VariableIndex> getVariables() {
      return variList;
    }

    public Iterable<Coordinate> getCoordinates() {
      return coords;
    }

    // unique name for Group
    public String getId() {
      return horizCoordSys.getId();
    }

    // human readable
    public String getDescription() {
      return horizCoordSys.getDescription();
    }

    public GdsHorizCoordSys getGdsHorizCoordSys() {
      return horizCoordSys.getHcs();
    }

    public int getGdsHash() {
      return horizCoordSys.getGdsHash();
    }

    @Override
    public int compareTo(GroupGC o) {
      return getDescription().compareTo(o.getDescription());
    }

    public List<MFile> getFiles() {
      List<MFile> result = new ArrayList<>();
      if (filenose == null) return result;
      for (int fileno : filenose)
        result.add(fileMap.get(fileno));
      Collections.sort(result);
      return result;
    }

    public List<String> getFilenames() {
      List<String> result = new ArrayList<>();
      if (filenose == null) return result;
      for (int fileno : filenose)
        result.add(fileMap.get(fileno).getPath());
      Collections.sort(result);
      return result;
    }

    public GribCollectionMutable.VariableIndex findVariableByHash(int cdmHash) {
      if (varMap == null) {
        varMap = new HashMap<>(variList.size() * 2);
        for (VariableIndex vi : variList)
          varMap.put(vi.cdmHash, vi);
      }
      return varMap.get(cdmHash);
    }

    private CalendarDateRange dateRange = null;

    public CalendarDateRange getCalendarDateRange() {
      if (dateRange == null) {
        CalendarDateRange result = null;
        for (Coordinate coord : coords) {
          switch (coord.getType()) {
            case time:
            case timeIntv:
            case time2D:
              CoordinateTimeAbstract time = (CoordinateTimeAbstract) coord;
              CalendarDateRange range = time.makeCalendarDateRange(null);
              if (result == null) result = range;
              else result = result.extend(range);
          }
        }
        dateRange = result;
      }
      return dateRange;
    }

    public int getNFiles() {
      if (filenose == null) return 0;
      return filenose.length;
    }

    public int getNCoords() {
      return coords.size();
    }

    public int getNVariables() {
      return variList.size();
    }

    public void show(Formatter f) {
      f.format("Group %s (%d) isTwoD=%s%n", horizCoordSys.getId(), getGdsHash(), isTwod);
      f.format(" nfiles %d%n", filenose == null ? 0 : filenose.length);
      f.format(" hcs = %s%n", horizCoordSys.getHcs());
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder("GroupGC{");
      sb.append(GribCollectionMutable.this.getName());
      sb.append(" isTwoD=").append(isTwod);
      sb.append('}');
      return sb.toString();
    }
  }

  public GribCollectionMutable.VariableIndex makeVariableIndex(GroupGC g, int cdmHash, int discipline, GribTables customizer,
                                                        byte[] rawPds, List<Integer> index, long recordsPos, int recordsLen) {
    return new VariableIndex(g, discipline, customizer, rawPds, cdmHash, index, recordsPos, recordsLen);
  }

  public GribCollectionMutable.VariableIndex makeVariableIndex(GroupGC g, VariableIndex other) {
    return new VariableIndex(g, other);
  }

  public class VariableIndex implements Comparable<VariableIndex> {
    public final GroupGC group;     // belongs to this group
    public final int tableVersion;   // grib1 only : can vary by variable
    public final int discipline;     // grib2 only
    public final byte[] rawPds;      // grib1 or grib2
    public final int cdmHash;
    public final long recordsPos;    // where the records array is stored in the index. 0 means no records
    public final int recordsLen;

    List<Integer> coordIndex;  // indexes into group.coords

    private SparseArray<Record> sa;   // for GC only; lazily read; same array shape as variable, minus x and y

    // partition only
    TwoDTimeInventory twot;  // twoD only  LOOK is this needed except when building ?? LOOK can we move to Partition ??
    SmartArrayInt time2runtime; // oneD only: for each timeIndex, which runtime coordinate does it use? 1-based so 0 = missing;
                             // index into the corresponding 2D variable's runtime coordinate

    // derived from pds
    public final int category, parameter, levelType, intvType, ensDerivedType, probType;
    private String intvName;  // eg "mixed intervals, 3 Hour, etc"
    public final String probabilityName;
    public final boolean isLayer, isEnsemble;
    public final int genProcessType;

    // stats
    public int ndups, nrecords, missing, totalSize;
    public float density;

    // temporary storage while building - do not use
    List<Coordinate> coords;

    private VariableIndex(GroupGC g, int discipline, GribTables customizer, byte[] rawPds,
                          int cdmHash, List<Integer> index, long recordsPos, int recordsLen) {
      this.group = g;
      this.discipline = discipline;
      this.rawPds = rawPds;
      this.cdmHash = cdmHash;
      this.coordIndex = index;
      this.recordsPos = recordsPos;
      this.recordsLen = recordsLen;

      if (isGrib1) {
        Grib1Customizer cust = (Grib1Customizer) customizer;
        Grib1SectionProductDefinition pds = new Grib1SectionProductDefinition(rawPds);

        // quantities that are stored in the pds
        this.category = 0;
        this.tableVersion = pds.getTableVersion();
        this.parameter = pds.getParameterNumber();
        this.levelType = pds.getLevelType();
        Grib1ParamTime ptime = pds.getParamTime(cust);
        if (ptime.isInterval()) {
          this.intvType = pds.getTimeRangeIndicator();
        } else {
          this.intvType = -1;
        }
        this.isLayer = cust.isLayer(pds.getLevelType());

        this.ensDerivedType = -1;
        this.probType = -1;
        this.probabilityName = null;

        this.genProcessType = pds.getGenProcess(); // LOOK process vs process type ??
        this.isEnsemble = pds.isEnsemble();

      } else {
        Grib2SectionProductDefinition pdss = new Grib2SectionProductDefinition(rawPds);
        Grib2Pds pds = null;
        try {
          pds = pdss.getPDS();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        this.tableVersion = -1;

        // quantities that are stored in the pds
        this.category = pds.getParameterCategory();
        this.parameter = pds.getParameterNumber();
        this.levelType = pds.getLevelType1();
        this.intvType = pds.getStatisticalProcessType();
        this.isLayer = Grib2Utils.isLayer(pds);

        if (pds.isEnsembleDerived()) {
          Grib2Pds.PdsEnsembleDerived pdsDerived = (Grib2Pds.PdsEnsembleDerived) pds;
          ensDerivedType = pdsDerived.getDerivedForecastType(); // derived type (table 4.7)
        } else {
          this.ensDerivedType = -1;
        }

        if (pds.isProbability()) {
          Grib2Pds.PdsProbability pdsProb = (Grib2Pds.PdsProbability) pds;
          probabilityName = pdsProb.getProbabilityName();
          probType = pdsProb.getProbabilityType();
        } else {
          this.probType = -1;
          this.probabilityName = null;
        }

        this.genProcessType = pds.getGenProcessType();
        this.isEnsemble = pds.isEnsemble();
      }
    }

    protected VariableIndex(GroupGC g, VariableIndex other) {
      this.group = g;
      this.tableVersion = other.tableVersion;
      this.discipline = other.discipline;
      this.rawPds = other.rawPds;
      this.cdmHash = other.cdmHash;
      this.coordIndex = new ArrayList<>(other.coordIndex);
      this.recordsPos = 0;
      this.recordsLen = 0;

      this.category = other.category;
      this.parameter = other.parameter;
      this.levelType = other.levelType;
      this.intvType = other.intvType;
      this.isLayer = other.isLayer;
      this.ensDerivedType = other.ensDerivedType;
      this.probabilityName = other.probabilityName;
      this.probType = other.probType;
      this.genProcessType = other.genProcessType;
      this.isEnsemble = other.isEnsemble;

      this.time2runtime = other.time2runtime;
      this.twot = other.twot;   // LOOK why did i delete this before ??
    }

    public List<Coordinate> getCoordinates() {
      List<Coordinate> result = new ArrayList<>(coordIndex.size());
      for (int idx : coordIndex)
        result.add(group.coords.get(idx));
      return result;
    }

    public CoordinateTimeAbstract getCoordinateTime() {
      Coordinate ctP = getCoordinate(Coordinate.Type.time);
      if (ctP == null) ctP = getCoordinate(Coordinate.Type.timeIntv);
      if (ctP == null) ctP = getCoordinate(Coordinate.Type.time2D);
      return (CoordinateTimeAbstract) ctP;
    }

    public Coordinate getCoordinate(Coordinate.Type want) {
      for (int idx : coordIndex)
        if (group.coords.get(idx).getType() == want)
          return group.coords.get(idx);
      return null;
    }

    public int getCoordinateIdx(Coordinate.Type want) {
      for (int idx : coordIndex)
        if (group.coords.get(idx).getType() == want)
          return idx;
      return -1;
    }

    public Coordinate getCoordinate(int index) {
      int grpIndex = coordIndex.get(index);
      return group.coords.get(grpIndex);
    }

    public int getCoordinateIndex(Coordinate.Type want) {
      for (int idx : coordIndex)
        if (group.coords.get(idx).getType() == want)
          return idx;
      return -1;
    }

    public Iterable<Integer> getCoordinateIndex() {
      return coordIndex;
    }

    public String getTimeIntvName() {
      if (intvName != null) return intvName;
      CoordinateTimeIntv timeiCoord = (CoordinateTimeIntv) getCoordinate(Coordinate.Type.timeIntv);
      if (timeiCoord != null) {
        intvName = timeiCoord.getTimeIntervalName();
        return intvName;
      }

      CoordinateTime2D time2DCoord = (CoordinateTime2D) getCoordinate(Coordinate.Type.time2D);
      if (time2DCoord == null || !time2DCoord.isTimeInterval()) return null;
      intvName = time2DCoord.getTimeIntervalName();
      return intvName;
    }

    public synchronized SparseArray<Record> getSparseArray() {
      return sa;
    }

    /////////////////////////////
    public String id() {
      return discipline + "-" + category + "-" + parameter;
    }

    public int getVarid() {
      return (discipline << 16) + (category << 8) + parameter;
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder();
      sb.append("VariableIndex");
      sb.append("{tableVersion=").append(tableVersion);
      sb.append(", discipline=").append(discipline);
      sb.append(", category=").append(category);
      sb.append(", parameter=").append(parameter);
      sb.append(", levelType=").append(levelType);
      sb.append(", intvType=").append(intvType);
      sb.append(", ensDerivedType=").append(ensDerivedType);
      sb.append(", probType=").append(probType);
      sb.append(", intvName='").append(intvName).append('\'');
      sb.append(", probabilityName='").append(probabilityName).append('\'');
      sb.append(", isLayer=").append(isLayer);
      sb.append(", genProcessType=").append(genProcessType);
      sb.append(", cdmHash=").append(cdmHash);
      //sb.append(", partTimeCoordIdx=").append(partTimeCoordIdx);
      sb.append('}');
      return sb.toString();
    }

    public String toStringComplete() {
      final StringBuilder sb = new StringBuilder();
      sb.append("VariableIndex");
      sb.append("{tableVersion=").append(tableVersion);
      sb.append(", discipline=").append(discipline);
      sb.append(", category=").append(category);
      sb.append(", parameter=").append(parameter);
      sb.append(", levelType=").append(levelType);
      sb.append(", intvType=").append(intvType);
      sb.append(", ensDerivedType=").append(ensDerivedType);
      sb.append(", probType=").append(probType);
      sb.append(", intvName='").append(intvName).append('\'');
      sb.append(", probabilityName='").append(probabilityName).append('\'');
      sb.append(", isLayer=").append(isLayer);
      sb.append(", cdmHash=").append(cdmHash);
      sb.append(", recordsPos=").append(recordsPos);
      sb.append(", recordsLen=").append(recordsLen);
      sb.append(", group=").append(group.getId());
      //sb.append(", partTimeCoordIdx=").append(partTimeCoordIdx);
      sb.append("}\n");
      if (time2runtime == null) sb.append("time2runtime is null");
      else {
        sb.append("time2runtime=");
        for (int idx=0; idx < time2runtime.getN(); idx++)
          sb.append(time2runtime.get(idx)).append(",");
      }
      return sb.toString();
    }

    public String toStringShort() {
      Formatter sb = new Formatter();
      sb.format("Variable {%d-%d-%d", discipline, category, parameter);
      sb.format(", levelType=%d", levelType);
      sb.format(", intvType=%d", intvType);
      if (intvName != null && intvName.length() > 0) sb.format(" intv=%s", intvName);
      if (probabilityName != null && probabilityName.length() > 0) sb.format(" prob=%s", probabilityName);
      sb.format(" cdmHash=%d}", cdmHash);
      return sb.toString();
    }

    public String toStringFrom() {
      Formatter sb = new Formatter();
      sb.format("Variable {%d-%d-%d", discipline, category, parameter);
      sb.format(", levelType=%d", levelType);
      sb.format(", intvType=%d", intvType);
      sb.format(", group=%s}", group);
      return sb.toString();
    }

    public synchronized void readRecords() throws IOException {
      if (this.sa != null) return;

      if (recordsLen == 0) return;
      byte[] b = new byte[recordsLen];

      if (indexRaf != null) {
        indexRaf.seek(recordsPos);
        indexRaf.readFully(b);
      } else {
        String idxPath = getIndexFilepathInCache();
        try (RandomAccessFile raf = RandomAccessFile.acquire(idxPath)) {
          raf.seek(recordsPos);
          raf.readFully(b);
        }
      }

      /*
      message SparseArray {
        required fixed32 cdmHash = 1; // which variable
        repeated uint32 size = 2;     // multidim sizes
        repeated uint32 track = 3;    // 1-based index into record list, 0 == missing
        repeated Record records = 4;  // List<Record>
      }
     */
      GribCollectionProto.SparseArray proto = GribCollectionProto.SparseArray.parseFrom(b);
      int cdmHash = proto.getCdmHash();
      if (cdmHash != this.cdmHash)
        throw new IllegalStateException("Corrupted index");

      int nsizes = proto.getSizeCount();
      int[] size = new int[nsizes];
      for (int i = 0; i < nsizes; i++)
        size[i] = proto.getSize(i);

      int ntrack = proto.getTrackCount();
      int[] track = new int[ntrack];
      for (int i = 0; i < ntrack; i++)
        track[i] = proto.getTrack(i);

      int n = proto.getRecordsCount();
      List<Record> records = new ArrayList<>(n);
      for (int i = 0; i < n; i++) {
        GribCollectionProto.Record pr = proto.getRecords(i);
        records.add(new Record(pr.getFileno(), pr.getPos(), pr.getBmsPos(), pr.getScanMode()));
      }

      this.sa = new SparseArray<>(size, track, records, 0);
    }

    @Override
    public int compareTo(VariableIndex o) {
      int r = discipline - o.discipline;  // LOOK add center, subcenter, version?
      if (r != 0) return r;
      r = category - o.category;
      if (r != 0) return r;
      r = parameter - o.parameter;
      if (r != 0) return r;
      r = levelType - o.levelType;
      if (r != 0) return r;
      r = intvType - o.intvType;
      return r;
    }

    public void calcTotalSize() {
      this.totalSize = 1;
      for (int idx : this.coordIndex) {
        Coordinate coord = this.group.coords.get(idx);
        if (coord instanceof CoordinateTime2D)
          this.totalSize *= ((CoordinateTime2D) coord).getNtimes();
        else
          this.totalSize *= coord.getSize();
      }
      this.density = ((float) this.nrecords) / this.totalSize;
    }
  }  // VariableIndex

  @Immutable
  public static class Record {
    public final int fileno;    // which file
    public final long pos;      // offset on file where data starts
    public final long bmsPos;   // if non-zero, offset where bms starts
    public final int scanMode;  // from gds

    public Record(int fileno, long pos, long bmsPos, int scanMode) {
      this.fileno = fileno;
      this.pos = pos;
      this.bmsPos = bmsPos;
      this.scanMode = scanMode;
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder("GribCollection.Record{");
      sb.append("fileno=").append(fileno);
      sb.append(", pos=").append(pos);
      sb.append(", bmsPos=").append(bmsPos);
      sb.append(", scanMode=").append(scanMode);
      sb.append('}');
      return sb.toString();
    }
  }

  public void showIndex(Formatter f) {
    f.format("Class (%s)%n", getClass().getName());
    f.format("%s%n%n", toString());

    //f.format(" master runtime coordinate%n");
    //masterRuntime.showCoords(f);
    //f.format("%n");

    for (Dataset ds : datasets) {
      f.format("Dataset %s%n", ds.type);
      for (GroupGC g : ds.groups) {
        f.format(" Group %s%n", g.horizCoordSys.getId());
        for (VariableIndex v : g.variList) {
          f.format("  %s%n", v.toStringShort());
        }
      }
    }
    if (fileMap == null) {
      f.format("Files empty%n");
    } else {
      f.format("Files (%d)%n", fileMap.size());
      for (int index : fileMap.keySet()) {
        f.format("  %d: %s%n", index, fileMap.get(index));
      }
      f.format("%n");
    }

  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("GribCollection{");
    sb.append("\nname='").append(name).append('\'');
    sb.append("\n directory=").append(directory);
    sb.append("\n config=").append(config);
    sb.append("\n isGrib1=").append(isGrib1);
    sb.append("\n version=").append(version);
    sb.append("\n center=").append(center);
    sb.append("\n subcenter=").append(subcenter);
    sb.append("\n master=").append(master);
    sb.append("\n local=").append(local);
    sb.append("\n genProcessType=").append(genProcessType);
    sb.append("\n genProcessId=").append(genProcessId);
    sb.append("\n backProcessId=").append(backProcessId);
    sb.append("\n}");
    return sb.toString();
  }

  public GroupGC makeGroup() {
    return new GroupGC();
  }

}
