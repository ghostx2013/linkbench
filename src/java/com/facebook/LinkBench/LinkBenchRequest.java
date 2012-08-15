package com.facebook.LinkBench;

import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.facebook.LinkBench.LinkStore.LinkStoreOp;

public class LinkBenchRequest implements Runnable {
  
  private final Logger logger = Logger.getLogger(ConfigUtil.LINKBENCH_LOGGER);
  Properties props;
  LinkStore store;
  RequestProgress progressTracker;

  long nrequests;
  long maxtime;
  int nrequesters;
  int requesterID;
  long randomid2max;
  Random random_id2;
  long maxid1;
  long startid1;
  int datasize;
  Level debuglevel;
  long progressfreq_ms;
  String dbid;
  boolean singleAssoc = false;

  // cummulative percentages
  double pc_addlink;
  double pc_deletelink;
  double pc_updatelink;
  double pc_countlink;
  double pc_getlink;
  double pc_getlinklist;

  LinkBenchStats stats;
  LinkBenchLatency latencyStats;

  long numfound;
  long numnotfound;

  // A random number generator for request type
  Random random_reqtype;

  // A random number generator for id1 of the request
  Random random_id1;

  Link link;

  // #links distribution from properties file
  int nlinks_func;
  int nlinks_config;
  int nlinks_default;

  long requestsdone = 0;

  // configuration for generating id2
  int id2gen_config;

  private int wr_distrfunc;

  private int wr_distrconfig;

  private int rd_distrfunc;

  private int rd_distrconfig;
  public LinkBenchRequest(LinkStore input_store,
                          Properties input_props,
                          LinkBenchLatency latencyStats,
                          RequestProgress progressTracker,
                          int input_requesterID,
                          int input_nrequesters) {

    store = input_store;
    props = input_props;
    this.latencyStats = latencyStats;
    this.progressTracker = progressTracker;

    nrequesters = input_nrequesters;
    requesterID = input_requesterID;
    if (requesterID < 0 ||  requesterID >= nrequesters) {
      throw new IllegalArgumentException("Bad requester id " 
          + requesterID + "/" + nrequesters);
    }
    
    nrequests = Long.parseLong(props.getProperty("requests"));
    maxtime = Long.parseLong(props.getProperty("maxtime"));
    maxid1 = Long.parseLong(props.getProperty("maxid1"));
    startid1 = Long.parseLong(props.getProperty("startid1"));

    // math functions may cause problems for id1 = 0. Start at 1.
    if (startid1 <= 0) {
      startid1 = 1;
    }

    // is this a single assoc test?
    if (startid1 + 1 == maxid1) {
      singleAssoc = true;
      logger.info("Testing single row assoc read.");
    }

    datasize = Integer.parseInt(props.getProperty("datasize"));
    debuglevel = ConfigUtil.getDebugLevel(props);
    dbid = props.getProperty("dbid");

    pc_addlink = Double.parseDouble(props.getProperty("addlink"));
    pc_deletelink = pc_addlink + Double.parseDouble(props.getProperty("deletelink"));
    pc_updatelink = pc_deletelink + Double.parseDouble(props.getProperty("updatelink"));
    pc_countlink = pc_updatelink + Double.parseDouble(props.getProperty("countlink"));
    pc_getlink = pc_countlink + Double.parseDouble(props.getProperty("getlink"));
    pc_getlinklist = pc_getlink + Double.parseDouble(props.getProperty("getlinklist"));

    wr_distrfunc = Integer.parseInt(props.getProperty("write_function"));
    wr_distrconfig = Integer.parseInt(props.getProperty("write_config"));
    rd_distrfunc = Integer.parseInt(props.getProperty("read_function"));
    rd_distrconfig = Integer.parseInt(props.getProperty("read_config"));


    if (Math.abs(pc_getlinklist - 100.0) > 1e-5) {//compare real numbers
      logger.error("Percentages of request types do not add to 100!");
      System.exit(1);
    }

    numfound = 0;
    numnotfound = 0;

    long displayfreq = Long.parseLong(props.getProperty("displayfreq"));
    String progressfreq = props.getProperty("progressfreq");
    if (progressfreq == null) {
      progressfreq_ms = 6000L;
    } else {
      progressfreq_ms = Long.parseLong(progressfreq) * 1000L;
    }
    int maxsamples = Integer.parseInt(props.getProperty("maxsamples"));
    stats = new LinkBenchStats(requesterID, displayfreq, maxsamples);

    // A random number generator for request type
    random_reqtype = new Random();

    // A random number generator for id1 of the request
    random_id1 = new Random();

    // random number generator for id2
    randomid2max = Long.parseLong(props.getProperty("randomid2max"));
    random_id2 = new Random();

    // configuration for generating id2
    id2gen_config = Integer.parseInt(props.getProperty("id2gen_config"));

    link = new Link();

    nlinks_func = Integer.parseInt(props.getProperty("nlinks_func"));
    nlinks_config = Integer.parseInt(props.getProperty("nlinks_config"));
    nlinks_default = Integer.parseInt(props.getProperty("nlinks_default"));
    if (nlinks_func == -2) {//real distribution has its own initialization
      try {
        //in case there is no load phase, real distribution
        //will be initialized here
        RealDistribution.loadOneShot(props);
      } catch (Exception e) {
        logger.error(e);
        System.exit(1);
      }
    }
  }

  public long getRequestsDone() {
    return requestsdone;
  }

  // gets id1 for the request based on desired distribution
  private long getid1_from_distribution(Random random_id1, boolean write,
                                        long previousId1) {

    // Need to pick a random number between startid1 (inclusive) and maxid1
    // (exclusive)
    long longid1 = startid1 +
      Math.abs(random_id1.nextLong())%(maxid1 - startid1);
    double id1 = longid1;
    double drange = (double)maxid1 - startid1;

    int distrfunc = write ? wr_distrfunc : rd_distrfunc;
    int distrconfig = write ? wr_distrconfig : rd_distrconfig;
    
    long newid1;
    switch(distrfunc) {

    case -3: //sequential from startid1 to maxid1 (circular)
      if (previousId1 <= 0) {
        newid1 = startid1;
      } else {
        newid1 = previousId1+1;
        if (newid1 > maxid1) {
          newid1 = startid1;
        }
      }
      break;

    case -2: //real distribution
      newid1 = RealDistribution.getNextId1(startid1, maxid1, write);
      break;

    case -1 : // inverse function f(x) = 1/x.
      newid1 = (long)(Math.ceil(drange/id1));
      break;
    case 0 : // generate id1 that is even multiple of distrconfig
      newid1 = distrconfig * (long)(Math.ceil(id1/distrconfig));
      break;
    case 100 : // generate id1 that is power of distrconfig
      double log = Math.ceil(Math.log(id1)/Math.log(distrconfig));

      newid1 = (long)Math.pow(distrconfig, log);
      break;
    default: // generate id1 that is perfect square if distrfunc is 2,
      // perfect cube if distrfunc is 3 etc

      // get the nth root where n = distrconfig
      long nthroot = (long)Math.ceil(Math.pow(id1, (1.0)/distrfunc));

      // get nthroot raised to power n
      newid1 = (long)Math.pow(nthroot, distrfunc);
      break;
    }

    if ((newid1 >= startid1) && (newid1 < maxid1)) {
      if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
        logger.trace("id1 generated = " + newid1 +
                             " for (distrfunc, distrconfig): " +
                             distrfunc + "," +  distrconfig);
      }

      return newid1;
    } else if (newid1 < startid1) {
      longid1 = startid1;
    } else if (newid1 >= maxid1) {
      longid1 = maxid1 - 1;
    }

    if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
      logger.debug("Using " + longid1 + " as id1 generated = " + newid1 +
                         " out of bounds for (distrfunc, distrconfig): " +
                         distrfunc + "," +  distrconfig);
    }

    return longid1;
  }

  private void onerequest(long requestno) {

    double r = Math.random() * 100.0;

    long starttime = 0;
    long endtime = 0;

    LinkStoreOp type = LinkStoreOp.UNKNOWN; // initialize to invalid value

    try {

      if (r <= pc_addlink) {
        // generate add request
        type = LinkStoreOp.ADD_LINK;
        link.id1 = getid1_from_distribution(random_id1, true, link.id1);
        starttime = System.nanoTime();
        addLink(link);
        endtime = System.nanoTime();
      } else if (r <= pc_deletelink) {
        type = LinkStoreOp.DELETE_LINK;
        link.id1 = getid1_from_distribution(random_id1, true, link.id1);
        starttime = System.nanoTime();
        deleteLink(link);
        endtime = System.nanoTime();
      } else if (r <= pc_updatelink) {
        type = LinkStoreOp.UPDATE_LINK;
        link.id1 = getid1_from_distribution(random_id1, true, link.id1);
        starttime = System.nanoTime();
        updateLink(link);
        endtime = System.nanoTime();
      } else if (r <= pc_countlink) {

        type = LinkStoreOp.COUNT_LINK;

        link.id1 = getid1_from_distribution(random_id1, false, link.id1);
        starttime = System.nanoTime();
        countLinks(link);
        endtime = System.nanoTime();

      } else if (r <= pc_getlink) {

        type = LinkStoreOp.GET_LINK;


        link.id1 = getid1_from_distribution(random_id1, false, link.id1);


        long nlinks = LinkBenchLoad.getNlinks(link.id1, startid1, maxid1,
            nlinks_func, nlinks_config, nlinks_default);

        // id1 is expected to have nlinks links. Retrieve one of those.
        link.id2 = (randomid2max == 0 ?
                     (maxid1 + link.id1 + random_id2.nextInt((int)nlinks + 1)) :
                     random_id2.nextInt((int)randomid2max));

        starttime = System.nanoTime();
        boolean found = getLink(link);
        endtime = System.nanoTime();

        if (found) {
          numfound++;
        } else {
          numnotfound++;
        }

      } else if (r <= pc_getlinklist) {

        type = LinkStoreOp.GET_LINKS_LIST;

        link.id1 = getid1_from_distribution(random_id1, false, link.id1);
        starttime = System.nanoTime();
        Link links[] = getLinkList(link);
        endtime = System.nanoTime();
        int count = ((links == null) ? 0 : links.length);

        stats.addStats(LinkStoreOp.RANGE_SIZE, count, false);
        if (Level.TRACE.isGreaterOrEqual(debuglevel)) {
          logger.trace("getlinklist count = " + count);
        }
      } else {
        logger.warn("No-op in requester: last probability < 1.0");
        return;
      }


      // convert to microseconds
      long timetaken = (endtime - starttime)/1000;

      // record statistics
      stats.addStats(type, timetaken, false);
      latencyStats.recordLatency(requesterID, type, timetaken);

    } catch (Throwable e){//Catch exception if any

      long endtime2 = System.nanoTime();

      long timetaken2 = (endtime2 - starttime)/1000;

      logger.error(LinkStore.displayName(type) + "error " +
                         e.getMessage(), e);

      stats.addStats(type, timetaken2, true);
      store.clearErrors(requesterID);
      return;
    }


  }

  @Override
  public void run() {
    logger.info("Requester thread #" + requesterID + " started: will do "
        + nrequests + " ops");
    long starttime = System.currentTimeMillis();
    long endtime = starttime + maxtime * 1000;
    long lastupdate = starttime;
    long curtime = 0;
    long i;

    if (singleAssoc) {
      LinkStoreOp type = LinkStoreOp.UNKNOWN;
      try {
        // add a single assoc to the database
        link.id1 = 45;
        link.id1 = 46;
        type = LinkStoreOp.ADD_LINK;
        addLink(link);

        // read this assoc from the database over and over again
        type = LinkStoreOp.GET_LINK;
        for (i = 0; i < nrequests; i++) {
          boolean found = getLink(link);
          if (found) {
            requestsdone++;
          } else {
            logger.warn("ThreadID = " + requesterID +
                               " not found link for id1=45");
          }
        }
      } catch (Throwable e) {
        logger.error(LinkStore.displayName(type) + "error " +
                         e.getMessage(), e);
      }
      return;
    }
    
    int requestsSinceLastUpdate = 0;
    for (i = 0; i < nrequests; i++) {
      onerequest(i);
      requestsdone++;
      
      curtime = System.currentTimeMillis();
      
      if (curtime > lastupdate + progressfreq_ms) {
        logger.info(String.format("Requester #%d %d/%d requests done",
            requesterID, requestsdone, nrequests));
        lastupdate = curtime;
      }
      
      requestsSinceLastUpdate++;
      if (curtime > endtime) {
        break;
      }
      if (requestsSinceLastUpdate >= RequestProgress.THREAD_REPORT_INTERVAL) {
        progressTracker.update(requestsSinceLastUpdate);
        requestsSinceLastUpdate = 0;
      }
    }
    
    progressTracker.update(requestsSinceLastUpdate);

    stats.displayStats(Arrays.asList(
        LinkStoreOp.GET_LINK, LinkStoreOp.GET_LINKS_LIST,
        LinkStoreOp.COUNT_LINK,
        LinkStoreOp.UPDATE_LINK, LinkStoreOp.ADD_LINK, 
        LinkStoreOp.RANGE_SIZE));
    logger.info("ThreadID = " + requesterID +
                       " total requests = " + i +
                       " requests/second = " + ((1000 * i)/(curtime - starttime)) +
                       " found = " + numfound +
                       " not found = " + numnotfound);

  }

  boolean getLink(Link link) throws Exception {
    return (store.getLink(dbid, link.id1, link.link_type, link.id2) != null ?
            true :
            false);
  }

  Link[] getLinkList(Link link) throws Exception {
    return store.getLinkList(dbid, link.id1, link.link_type);
  }

  long countLinks(Link link) throws Exception {
    return store.countLinks(dbid, link.id1, link.link_type);
  }

  // return a new id2 that satisfies 3 conditions:
  // 1. close to current id2 (can be slightly smaller, equal, or larger);
  // 2. new_id2 % nrequesters = requestersId;
  // 3. smaller or equal to randomid2max unless randomid2max = 0
  private static long fixId2(long id2, long nrequesters,
                             long requesterID, long randomid2max) {

    long newid2 = id2 - (id2 % nrequesters) + requesterID;
    if ((newid2 > randomid2max) && (randomid2max > 0)) newid2 -= nrequesters;
    return newid2;
  }

  void addLink(Link link) throws Exception {
    link.link_type = LinkStore.LINK_TYPE;
    link.id1_type = LinkStore.ID1_TYPE;
    link.id2_type = LinkStore.ID2_TYPE;

    // note that use use write_function here rather than nlinks_func.
    // This is useful if we want request phase to add links in a different
    // manner than load phase.
    long nlinks = LinkBenchLoad.getNlinks(link.id1, startid1, maxid1,
        wr_distrfunc, wr_distrconfig, 1);

    // We want to sometimes add a link that already exists and sometimes
    // add a new link. So generate id2 between 0 and 2 * links.
    // unless randomid2max is non-zero (in which case just pick a random id2
    // upto randomid2max). Plus 1 is used to make nlinks atleast 1.
    nlinks = 2 * nlinks + 1;
    link.id2 = (randomid2max == 0 ?
                 (maxid1 + link.id1 + random_id2.nextInt((int)nlinks + 1)) :
                 random_id2.nextInt((int)randomid2max));

    if (id2gen_config == 1) {
      link.id2 = fixId2(link.id2, nrequesters, requesterID,
          randomid2max);
    }

    link.visibility = LinkStore.VISIBILITY_DEFAULT;
    link.version = 0;
    link.time = System.currentTimeMillis();

    // generate data as a sequence of random characters from 'a' to 'd'
    Random random_data = new Random();
    link.data = new byte[datasize];
    for (int k = 0; k < datasize; k++) {
      link.data[k] = (byte)('a' + Math.abs(random_data.nextInt()) % 4);
    }

    // no inverses for now
    store.addLink(dbid, link, true);

  }


  void updateLink(Link link) throws Exception {
    link.link_type = LinkStore.LINK_TYPE;


    long nlinks = LinkBenchLoad.getNlinks(link.id1, startid1, maxid1,
        nlinks_func, nlinks_config, nlinks_default);

    // id1 is expected to have nlinks links. Update one of those.
    link.id2 = (randomid2max == 0 ?
                 (maxid1 + link.id1 + random_id2.nextInt((int)nlinks + 1)) :
                 random_id2.nextInt((int)randomid2max));

    if (id2gen_config == 1) {
      link.id2 = fixId2(link.id2, nrequesters, requesterID,
        randomid2max);
    }

    link.id1_type = LinkStore.ID1_TYPE;
    link.id2_type = LinkStore.ID2_TYPE;
    link.visibility = LinkStore.VISIBILITY_DEFAULT;
    link.version = 0;
    link.time = System.currentTimeMillis();

    // generate data as a sequence of random characters from 'e' to 'h'
    Random random_data = new Random();
    link.data = new byte[datasize];
    for (int k = 0; k < datasize; k++) {
      link.data[k] = (byte)('e' + Math.abs(random_data.nextInt()) % 4);
    }

    // no inverses for now
    store.addLink(dbid, link, true);

  }

  void deleteLink(Link link) throws Exception {
    link.link_type = LinkStore.LINK_TYPE;

    long nlinks = LinkBenchLoad.getNlinks(link.id1, startid1, maxid1,
        nlinks_func, nlinks_config, nlinks_default);

    // id1 is expected to have nlinks links. Delete one of those.
    link.id2 = (randomid2max == 0 ?
                (maxid1 + link.id1 + random_id2.nextInt((int)nlinks + 1)) :
                random_id2.nextInt((int)randomid2max));

    if (id2gen_config == 1) {
      link.id2 = fixId2(link.id2, nrequesters, requesterID,
        randomid2max);
    }

    // no inverses for now
    store.deleteLink(dbid, link.id1, link.link_type, link.id2,
                     true, // no inverse
                     false);// let us hide rather than delete
  }

  public static class RequestProgress {
    // How many ops before a thread should register its progress
    static final int THREAD_REPORT_INTERVAL = 250;
    // How many ops before a progress update should be printed to console
    private static final int PROGRESS_PRINT_INTERVAL = 10000;
    
    private final Logger progressLogger;
    
    private long totalRequests;
    private final AtomicLong requestsDone;
    
    private long startTime;
    private long timeLimit_s;

    public RequestProgress(Logger progressLogger,
                      long totalRequests, long timeLimit) {
      this.progressLogger = progressLogger;
      this.totalRequests = totalRequests;
      this.requestsDone = new AtomicLong();
      this.timeLimit_s = timeLimit;
      this.startTime = 0;
    }
    
    public void startTimer() {
      startTime = System.currentTimeMillis();
    }
    
    public void update(long requestIncr) {
      long curr = requestsDone.addAndGet(requestIncr);
      long prev = curr - requestIncr;
      
      long interval = PROGRESS_PRINT_INTERVAL;
      if ((curr / interval) > (prev / interval) || curr == totalRequests) {
        float progressPercent = ((float) curr) / totalRequests * 100;
        long now = System.currentTimeMillis();
        long elapsed = now - startTime;
        float elapsed_s = ((float) elapsed) / 1000;
        float limitPercent = (elapsed_s / ((float) timeLimit_s)) * 100;
        float rate = curr / ((float)elapsed_s);
        progressLogger.info(String.format(
            "%d/%d requests finished: %.1f%% complete at %.1f ops/sec" +
            " %.1f/%d secs elapsed: %.1f%% of time limit used",
            curr, totalRequests, progressPercent, rate,
            elapsed_s, timeLimit_s, limitPercent));
            
      }
    }
  }

  public static RequestProgress createProgress(Logger logger,
       Properties props) {
    long total_requests = Long.parseLong(props.getProperty("requests"))
                      * Long.parseLong(props.getProperty("requesters"));
    return new RequestProgress(logger, total_requests,
        Long.parseLong(props.getProperty("maxtime")));
  }
}

