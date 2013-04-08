/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.analyst.batch;

import java.io.IOException;
import java.util.Collections;
import java.util.TimeZone;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Resource;

import lombok.Setter;

import org.opentripplanner.analyst.batch.aggregator.Aggregator;
import org.opentripplanner.analyst.core.Sample;
import org.opentripplanner.analyst.request.SampleFactory;
import org.opentripplanner.common.model.GenericLocation;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.error.VertexNotFoundException;
import org.opentripplanner.routing.services.GraphService;
import org.opentripplanner.routing.services.SPTService;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

public class BatchProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(BatchProcessor.class);
    private static final String EXAMPLE_CONTEXT = "batch-context.xml";
    
    @Autowired private GraphService graphService;
    @Autowired private SPTService sptService;
    @Autowired private SampleFactory sampleFactory;

    @Resource private Population origins;
    @Resource private Population destinations;
    @Resource private RoutingRequest prototypeRoutingRequest;

    @Setter private Aggregator aggregator;
    @Setter private Accumulator accumulator;
    @Setter private int logThrottleSeconds = 4;    
    @Setter private int searchCutoffSeconds = -1;
    
    /**
     * Empirical results for a 4-core processor (with 8 fake hyperthreading cores):
     * Throughput increases linearly with nThreads, up to the number of physical cores. 
     * Diminishing returns beyond 4 threads, but some improvement is seen up to 8 threads.
     * The default value includes the hyperthreading cores, so you may want to set nThreads 
     * manually in your IoC XML. 
     */
    @Setter private int nThreads = Runtime.getRuntime().availableProcessors(); 

    @Setter private String date = "2011-02-04";
    @Setter private String time = "08:00 AM";
    @Setter private TimeZone timeZone = TimeZone.getDefault();
    @Setter private String outputPath = "/tmp/analystOutput";
    @Setter private float checkpointIntervalMinutes = -1;
    
    enum Mode { BASIC, AGGREGATE, ACCUMULATE };
    private Mode mode;
    private long startTime = -1;
    private long lastLogTime = 0;
    private long lastCheckpointTime = 0;
    private ResultSet aggregateResultSet = null;
    
    
    //jb all source code and comment lines written or modified by author (John Brennan) contain the following comment containing the author's initials (jb) at the end of the line //jb
    //ambiguities with same line modifications will be explained in comment.
    //Everything else is part of the standard Opentripplanner project source code. //jb
    // the best way to find all such lines is to use ctrl+f ... //jb
    private static int totalLoop = 50; //jb
    private static int count = 0;    //jb
    private static final boolean AMENITY_RUN = false; //jb
    private static String selectedTransportMode; //jb
    //private static String[] transportModes = {"CAR","TRANSIT","WALK","BICYCLE"}; //jb
    //bicycle not working at the moment. And Transit cannot work without walking - must walk to bus stops etc. //jb
    private static String[] transportModes = {"CAR","TRANSIT,WALK","WALK"}; //this is the recommended one. //jb
    
    //these other combinations for testing. Can be removed... //jb
    //private static String[] transportModes = {"CAR","TRANSIT,WALK"}; //jb
    //private static String[] transportModes = {"TRANSIT,WALK","CAR"}; //jb
    //private static String[] transportModes = {"CAR"}; //jb
    //private static String[] transportModes = {"BICYCLE,WALK"}; //jb
    /*private static String[] transportModes = {"TRANSIT"};*/ //jb
    /*private static String[] transportModes = {"TRANSIT,WALK"};*/ //jb
    /*private static String[] transportModes = {"BICYCLE"};*/ //jb
       
    
    /** Cut off the search instead of building a full path tree. Can greatly improve run times. */
    public void setSearchCutoffMinutes(int minutes) {
        this.searchCutoffSeconds = minutes * 60;
    }
    
    public static void main(String[] args) throws IOException {
            	

    	//check time //jb
    	long startTime = System.nanoTime();        //jb add to assess performance of program.
    	
    	org.springframework.core.io.Resource appContextResource;
        if( args.length == 0) {
            LOG.warn("no configuration XML file specified; using example on classpath");
            appContextResource = new ClassPathResource(EXAMPLE_CONTEXT);
        } else {
            String configFile = args[0];
            appContextResource = new FileSystemResource(configFile);
        }
        GenericApplicationContext ctx = new GenericApplicationContext();
        XmlBeanDefinitionReader xmlReader = new XmlBeanDefinitionReader(ctx);
        xmlReader.loadBeanDefinitions(appContextResource);

        long refreshStartTime = System.nanoTime();         //jb
    
        ctx.refresh();

        calcProgramTime(refreshStartTime,"context refresh");         //jb       
       
        ctx.registerShutdownHook();
        BatchProcessor processor = ctx.getBean(BatchProcessor.class);

        //Use this for calculating amenity times //jb
        ///////////////////////////////////////////// //jb        
        if(AMENITY_RUN){          //jb
                            
            for(String mode: transportModes){         //jb
                
                System.out.println(mode);         //jb
                selectedTransportMode = mode;         //jb
            
                if (processor == null)
                {
                    LOG.error("No BatchProcessor bean was defined.");
                }
                else{               
                    processor.run();            
                }
                count ++;         //jb
            }
        }              
        else { //Use this for calculating the commute time //jb
            while(count < totalLoop)//jb
            {
            
                //comment out if testing
                if(count ==0)           //jb
                {
                    
                        ExternalInvoke.setUpConnection();         //jb
                }
                
                
                selectedTransportMode = ExternalInvoke.awaitRequest();         //jb

                //comment this out when not testing 
                //selectedTransportMode = "WALK,TRANSIT";         //jb
                //selectedTransportMode = "CAR";         //jb
                
                long requestLoopStartTime = System.nanoTime(); //jb
                
            	if (processor == null)
            	{
    	            LOG.error("No BatchProcessor bean was defined.");
            	}
    	        else{	            
    	            processor.run();	        
    	           }
                            
    	       
            	calcProgramTime(requestLoopStartTime,"request loop " + count ); //jb
    
            	count ++; //jb
        }
      }        
 

        
      //jb - added to assess performance of program
        calcProgramTime(startTime,"total main");       //  jb         
        
    }

    private void run() {
            	
        long originStartTime = System.nanoTime();//jb    	
    	origins.setup();
    	calcProgramTime(originStartTime,"origin setup ");   //jb

    	if (count == 0) //jb
    	{ //jb
    	    	long destinationsStartTime = System.nanoTime();//jb
    	        destinations.setup();
    	        calcProgramTime(destinationsStartTime,"destinations setup ");   //jb
    	        
    	        long linkStartTime = System.nanoTime();//jb
    	        linkIntoGraph(destinations);
    	        calcProgramTime(linkStartTime,"link into graph ");   //jb
    	}
    	        
        
        // Set up a thread pool to execute searches in parallel
        LOG.info("Number of threads: {}", nThreads);
        ExecutorService threadPool = Executors.newFixedThreadPool(nThreads);
        // ECS enqueues results in the order they complete (unlike invokeAll, which blocks)
        CompletionService<Void> ecs = new ExecutorCompletionService<Void>(threadPool);
        if (aggregator != null) {
            /* aggregate over destinations and save one value per origin */
            mode = Mode.AGGREGATE;
            aggregateResultSet = new ResultSet(origins); // results shaped like origins
        } else if (accumulator != null) { 
            /* accumulate data for each origin into all destinations */
            mode = Mode.ACCUMULATE;
            aggregateResultSet = new ResultSet(destinations); // results shaped like destinations
        } else { 
            /* neither aggregator nor accumulator, save a bunch of results */
            mode = Mode.BASIC;
            aggregateResultSet = null;
            if (!outputPath.contains("{}")) {
                LOG.error("output filename must contain origin placeholder.");
                System.exit(-1);
            }
        }
        
        int nTasks = 0;
        for (Individual oi : origins) { // using filtered iterator
            
            CyclicBarrier barrier = new CyclicBarrier(2); //cyclic barrier to ensure both tasks finish calculating results before they are written to file. //jb
            //submit two tasks - 1, for "to" direction and the other for the "from" direction //jb
            BatchAnalystTask toTripTask = new BatchAnalystTask(nTasks, oi, false, null, barrier); //jb
            
            ecs.submit(toTripTask, null); //jb 
            //pass reference to toTrip task into from trip task
            ecs.submit(new BatchAnalystTask(nTasks, oi, true, toTripTask, barrier), null); //jb
            
            nTasks += 2; //jb : 2 tasks were submited, so increment by 2
        }
        LOG.info("created {} tasks.", nTasks);
        int nCompleted = 0;
        try { // pull Futures off the queue as tasks are finished
            while (nCompleted < nTasks) {
                try {
                    ecs.take().get(); // call get to check for exceptions in the completed task
                    LOG.debug("got result {}/{}", nCompleted, nTasks);
                    if (checkpoint()) {
                        LOG.info("checkpoint written.");
                    }
                } catch (ExecutionException e) {
                    LOG.error("exception in thread task: {}", e);
                }
                ++nCompleted;
                projectRunTime(nCompleted, nTasks);
            }
        } catch (InterruptedException e) {
            LOG.warn("run was interrupted after {} tasks", nCompleted);
        }
        
        long closeStartTime = System.nanoTime();//jb
        
        threadPool.shutdown();
        if (accumulator != null)
            accumulator.finish();
        if (aggregateResultSet != null)
            aggregateResultSet.writeAppropriateFormat(outputPath);
        LOG.info("DONE.");
        
        origins.clearIndividuals(origins.getIndividuals()); //jb - this is needed to prevent multiple threads being created from previous runs.
        //origins.clear(); //jb
        calcProgramTime(closeStartTime,"close ");   //jb
        

    }

    private void projectRunTime(int current, int total) {
        long currentTime = System.currentTimeMillis();
        // not threadsafe, but the worst thing that will happen is a double log message 
        // anyway we are using this in the controller thread now
        if (currentTime > lastLogTime + logThrottleSeconds * 1000) {
            lastLogTime = currentTime;
            double runTimeMin = (currentTime - startTime) / 1000.0 / 60.0;
            double projectedMin = (total - current) * (runTimeMin / current);
            LOG.info("received {} results out of {}", current, total);
            LOG.info("running {} min, {} min remaining (projected)", (int)runTimeMin, (int)projectedMin);
        }
    }
    
    private boolean checkpoint() {
        if (checkpointIntervalMinutes < 0 || aggregateResultSet == null)
            return false;
        long currentTime = System.currentTimeMillis();
        // not threadsafe, but the worst thing that will happen is a double checkpoint
        // anyway, this is being called in the controller thread now
        if (currentTime > lastCheckpointTime + checkpointIntervalMinutes * 60 * 1000) {
            lastCheckpointTime = currentTime;
            aggregateResultSet.writeAppropriateFormat(outputPath);
            return true;
        }
        return false;
    }
    
    private RoutingRequest buildRequest(Individual i, boolean isArriveBy) { //jb - I only added the isArriveBy argument on this line. this method was previously there.
        RoutingRequest req = prototypeRoutingRequest.clone();
        
            req.setArriveBy(isArriveBy);        //jb                
            
            req.setModes(new TraverseModeSet(selectedTransportMode)); //jb
        req.setDateTime(date, time, timeZone);
        if (searchCutoffSeconds > 0) {
            req.worstTime = req.dateTime + (req.arriveBy ? -searchCutoffSeconds : searchCutoffSeconds);
        }
        
         
        GenericLocation latLon = new GenericLocation(i.lat, i.lon);
        req.batch = true;
        if (req.arriveBy)
            req.setTo(latLon);
        else
            req.setFrom(latLon);
        
        try {
            req.setRoutingContext(graphService.getGraph(req.routerId));
            return req;
        } catch (VertexNotFoundException vnfe) {
            LOG.debug("no vertex could be created near the origin point");
            return null;
        }
    }
      
    
    /** 
     * Generate samples for (i.e. non-invasively link into the Graph) only those individuals that 
     * were not rejected by filters. Other Individuals will have null samples, indicating that they 
     * should be skipped.
     */
    private void linkIntoGraph(Population p) {
        LOG.info("linking population {} to the graph...", p);
        int n = 0, nonNull = 0;
        for (Individual i : p) {
            Sample s = sampleFactory.getSample(i.lon, i.lat);
            i.sample = s;
            n += 1;
            if (s != null)
                nonNull += 1;
        }
        LOG.info("successfully linked {} individuals out of {}", nonNull, n);
    }
        
    /** 
     * A single computation to perform for a single origin.
     * Runnable, not Callable. We want accumulation to happen in the worker thread. 
     * Handling all accumulation in the controller thread risks amassing a queue of large 
     * result sets. 
     */
    private class BatchAnalystTask implements Runnable {
        
        protected final int i;
        protected final Individual oi;
        
        private boolean isArriveBy;         //jb
        private BatchAnalystTask toTripTask = null;        //jb, reference of the to thread for the from thread  need for designating the file writer
        private ResultSet results; //jb
        CyclicBarrier barrier; //jb
        
        
        public BatchAnalystTask(int i, Individual oi) {
            this.i = i;
            this.oi = oi;
        }
        
        public BatchAnalystTask(int i, Individual oi, boolean isArriveBy, BatchAnalystTask toTripTask, CyclicBarrier barrier) { //jb
            this.i = i; //jb
            this.oi = oi;//jb
            this.isArriveBy = isArriveBy;//jb
            this.barrier = barrier; //jb
            if(isArriveBy){//jb
                this.toTripTask = toTripTask;//jb
            }            //jb
        }        //jb
        
        public ResultSet getResults(){ //jb
            return results; //jb
        } //jb
        
        
        @Override
        public void run() {
                
                
                RoutingRequest req =null ;//jb
                long runStartTime = System.nanoTime();//jb
                     
            	LOG.debug("calling origin : {}", oi);
        	long rrStartTime = System.nanoTime(); //jb
                            	   
                req = buildRequest(oi, isArriveBy); //jb -simply added the extra isArriveBy argument
                calcProgramTime(rrStartTime,"build routing request total"); //jb
                
                if (req != null) {                	                    
                    	long sptStartTime = System.nanoTime(); //jb
                    	ShortestPathTree spt = sptService.getShortestPathTree(req);
                    	calcProgramTime(sptStartTime,"spt total"); //jb
                    	//long resultsTTStartTime = System.nanoTime(); //jb

                    	
                    	results = ResultSet.forTravelTimes(destinations, spt);
                    }
                
                try { //jb
                    barrier.await(); //jb //wait until both the to and from results have been calculated.
                } catch (InterruptedException e) { //jb
                    e.printStackTrace(); //jb
                } catch (BrokenBarrierException e) { //jb
                    e.printStackTrace(); //jb
                }  //jb
                       
                if(this.isArriveBy) //jb
                { //jb
                    ResultSet toResults = toTripTask.getResults();       //jb //since results holds the "from" direction results for this file writer thread. This line gets the "to" direction results from the other task.

                        if (req != null) {
                                req.cleanup();
                                    switch (mode) {
                                    case ACCUMULATE:
                                        synchronized (aggregateResultSet) {
                                            accumulator.accumulate(oi.input, toResults, aggregateResultSet); 
                                        }
                                        break;
                                    case AGGREGATE:
                                        aggregateResultSet.results[i] = aggregator.computeAggregate(toResults);
                                        break;
                                    default:
                                        if(AMENITY_RUN){ //jb
                                            String subName = outputPath.replace("{}", String.format("%s\\%s_%d", selectedTransportMode,oi.label, i));//jb altered the file name 
                                            toResults.writeAppropriateFormat(subName,results); //jb - method call sends both results arrays.                                            
                                        } //jb
                                        else{ //jb
                                            String subName = outputPath.replace("{}", String.format("%s_%d", oi.label, i));  
                                            toResults.writeAppropriateFormat(subName,results); //jb
                                            ExternalInvoke.finishNotify();//jb signal to the waiting app that results have been written to file and it can proceed.
                                        }
                                    }
                                        
                                }    
                                calcProgramTime(runStartTime,"run total");    //jb
                                
                }//end if                      //jb
             
              //jb//await again. This is mainly needed for the non-file writer thread so that it doesn't distract or use up resources while the writing is done as soon as possible and let the rails app continue. 
                        try { //jb
                            barrier.await(); //jb
                        } catch (InterruptedException e) { //jb
                            e.printStackTrace(); //jb
                        } catch (BrokenBarrierException e) { //jb
                            e.printStackTrace(); //jb
                        } //jb
                
                    } //jb
                
               
               
    }
    
    //jb based on some code inspired here:
    /*http://stackoverflow.com/questions/924208/java-how-do-you-convert-nanoseconds-to-seconds-using-the-timeunit-class //jb
        //http://stackoverflow.com/questions/6646467/how-to-find-time-taken-to-run-java-program //jb
*/    private static void calcProgramTime(Long startTime, String label){ //jb
    	   long endTime = System.nanoTime(); //jb
           long elapsedTime = endTime - startTime; //jb
           double seconds = (double)elapsedTime / 1000000000.0; //jb
           System.out.println(label + " Took "+ elapsedTime + " ns or " + seconds + " secs"); //jb
    } //jb
    
  
    
    
}

