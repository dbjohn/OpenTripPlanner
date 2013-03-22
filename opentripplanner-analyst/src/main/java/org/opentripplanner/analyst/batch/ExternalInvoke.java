package org.opentripplanner.analyst.batch;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

	/*
	 * JB 
	 * class to invoke batch processor when needed from external program
	 * 
	 */
public class ExternalInvoke {

	/**
	 * @param args
	 */
	private static ServerSocket server;
	private static int port = 55557;
	private static String signal = "Go";
	private static	Socket client;
	private static	int temp = 5; 
	private static	int count = 0;
	/*private static	String clientMessage; */
	private static PrintWriter out; 
	private static BufferedReader in; 

	//add startup connection method here
	
	public static void setUpConnection(){
		  try
			 {
			   server = new ServerSocket(port); 
			   server.setReuseAddress(true);
			   System.out.println("Connection set up on port:" + port);
			 }
		  catch(SocketException e)
                  {
                          System.out.println("Unable to set address as reusable");
                  }
		  catch(IOException e)
		    {
		       System.out.println("Unable to attach to port");
		       System.exit(-1);
		    }		
		 
	}
	
	public static String awaitRequest(){
		 //while (count < temp) { 
			 String transportModes = "";
	       try{
	    	   System.out.println("going to wait on accept");
	    	   client = server.accept(); 
	    	   /*DataInputStream in = new DataInputStream(client.getInputStream());
	    	   */
	    	   System.out.println("socket setup");

	    	    in = new BufferedReader(new InputStreamReader(client.getInputStream())); //blocks on waiting for input
	    	   //while ((input = in.readLine()) != null) ;
	    	    transportModes = in.readLine();
	    	
	    	   /*System.out.println("Sent modes----------S"); 
	    	   System.out.println(transportModes); */
	    	   		
	       }
	      /* catch(InterruptedException e){
	    	   e.printStackTrace();
	       }*/
	       catch(IOException e){
	    	   System.out.println("Problem occurred when reading from socket");
		       e.printStackTrace();	    	   
	       }
	       
	       return transportModes;	       
	       
	}

	
	public static void finishNotify()        
        {	        
    	    try {
                out = new PrintWriter(client.getOutputStream(), true);
            } catch (IOException e) {
                e.printStackTrace();
            }    
	    out.println("Continue");	    
        }
	
	public static void test(String arg)
	
	{
		System.out.println("From external invoke: " + arg);
		
	}
}
