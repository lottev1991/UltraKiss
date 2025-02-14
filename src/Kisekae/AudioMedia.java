package Kisekae ;

// Title:        Kisekae UltraKiss
// Version:      3.4  (May 11, 2023)
// Copyright:    Copyright (c) 2002-2023
// Author:       William Miles
// Description:  Kisekae Set System
//
//  This program is free software; you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation; either version 3 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program; if not, write to the Free Software
//  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA

/*
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
%  This copyright notice and this permission notice shall be included in      %
%  all copies or substantial portions of UltraKiss.                           %
%                                                                             %
%  The software is provided "as is", without warranty of any kind, express or %
%  implied, including but not limited to the warranties of merchantability,   %
%  fitness for a particular purpose and noninfringement.  In no event shall   %
%  William Miles be liable for any claim, damages or other liability,         %
%  whether in an action of contract, tort or otherwise, arising from, out of  %
%  or in connection with Kisekae UltraKiss or the use of UltraKiss.           %
%                                                                             %
%  William Miles                                                              %
%  144 Oakmount Rd. S.W.                                                      %
%  Calgary, Alberta                                                           %
%  Canada  T2V 4X4                                                            %
%                                                                             %
%  w.miles@wmiles.com                                                         %
%                                                                             %
%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
*/


/**
* Audio class
*
* Purpose:
*
* This class encapsulates an audio object.  Audio objects are accessed
* through FKiSS sound and music events.
*
* Audio objects use their file name as their access key.
*
* The intent is as follows:
*
* 1. Read an audio file into a memory buffer.
*
* 2. Realize the player.  The object is now initialized.  It does not play.
*    It waits in a realized state for a play request.
*
* 3. A play request prefetches and starts the player.  The play request can
*    come at any time.  The possible states are:
*
* 3.0 (Expected state) Init function complete, player waiting in a realized
*     state, and play is requested.
*		=> initiate prefetch.  On prefetch complete, start the player.
*
* 3.1 (Slow realize state) Init function complete, player in process of
*     realizing due to initialization, and play is requested.
*		=> Set callback switch so that when realize is complete, play is initiated.
*
* 3.2 (Slow prefetch state) Init function complete, player is prefetching, and
*     another play is requested.
*		=> Ignore the play request as the player will start when prefetch completes.
*
* 3.3 (Busy state) Init function complete, player is started, and another play
*     is requested.
*		=> restart the player with setMediaTime(0) and prefetch.
*
*/


import java.awt.*;
import java.io.* ;
import java.awt.event.* ;
import java.util.Hashtable ;
import java.util.Vector ;
import java.util.Enumeration ;
import java.net.URL ;
import java.util.Collections;
import java.util.concurrent.locks.ReentrantLock ;
import javax.media.* ;
import javax.media.protocol.* ;
import javax.media.control.BufferControl ;
import javax.swing.* ;


final class AudioMedia extends Audio
{
	private ContentDescriptor cd = null;	// The audio content type
	private MediaDataSource ds = null ;		// The audio data source
	private Object currentsound = null ; 	// Sequencer, player or clip
   private Object listener = null ;			// Last extra listener added
   private Object controller = null ;		// Internal listener
   private final Object waithold = new Object() ; 

	// Constructor for when we do not have a configuration.

	public AudioMedia(ArchiveFile zip, String file)
	{
		me = this ;
		setUniqueID(new Integer(this.hashCode())) ;
		init(zip,file,null) ;
	}

	// Constructor

	public AudioMedia(ArchiveFile zip, String file, Configuration ref)
	{
		me = this ;
		init(zip,file,ref) ;
	}


	// Object state reference methods
	// ------------------------------

	// Method to return the medi sequencer.

	Object getPlayer() { return (error) ? null : currentsound ; }

	// Method to return the content descriptor.

	Object getContentDescriptor() { return (error) ? null : cd ; }

	// Method to return the content descriptor.

	String getContentType()
	{
		if (cd == null) return super.getContentType() ;
		return cd.getContentType() ;
	}

   // Method to return the last listener object added.

   Object getListener() { return listener ; }

	// Return an indication if the medium is JMF sound.

	boolean isJavaMedia() { return true ; }


	// Return the object state.

	String getState()
	{
		String state = super.getState() ;
		if (!(currentsound instanceof Player)) return state ;
		int playerstate = ((Player) currentsound).getState() ;
		if (playerstate == Player.Prefetched)
         state = Kisekae.getCaptions().getString("MediaPrefetchedState") ;
		if (playerstate == Player.Prefetching)
         state = Kisekae.getCaptions().getString("MediaPrefetchingState") ;
		if (playerstate == Player.Realized)
         state = Kisekae.getCaptions().getString("MediaRealizedState") ;
		if (playerstate == Player.Realizing)
         state = Kisekae.getCaptions().getString("MediaRealizingState") ;
		if (playerstate == Player.Started)
         state = Kisekae.getCaptions().getString("MediaStartedState") ;
		if (playerstate == Player.Unrealized)
         state = Kisekae.getCaptions().getString("MediaUnrealizedState") ;
		return state ;
	}

	// Return the object position.

	int getPosition()
	{
		int position = super.getPosition() ;
		if (!(currentsound instanceof Player)) return position ;
		if (((Player) currentsound).getState() < Player.Realized) return position ;
		position = (int) (((Player) currentsound).getMediaTime().getSeconds() * 100) ;
		return position ;
	}

	// Return the object duration.

	int getDuration()
	{
		int duration = super.getDuration() ;
		if (!(currentsound instanceof Player)) return duration ;
		if (((Player) currentsound).getState() < Player.Realized) return duration ;
		duration = (int) (((Player) currentsound).getDuration().getSeconds()) ;
		return duration ;
	}

	// Return the object latency.

	int getLatency()
	{
		int latency = super.getLatency() ;
		if (!(currentsound instanceof Player)) return latency ;
		if (((Player) currentsound).getState() < Player.Realized) return latency ;
      Time t = ((Player) currentsound).getStartLatency() ;
      if (t == Controller.LATENCY_UNKNOWN) return latency ;
		latency = (int) (t.getSeconds() * 100) ;
		return latency ;
	}

	// Return the control panel.

	Component getControlPanelComponent()
	{
		if (!(currentsound instanceof Player)) return null ;
		Player p = (Player) currentsound ;
		if (p.getState() < Player.Realized) return null ;
		return p.getControlPanelComponent() ;
	}



	// Object loading methods
	// ----------------------



	// Audio player initialization. 	Create a sound data source from the
	// memory array.

	void init()
	{
      playcount = 0 ;
		started = false ;
      stopping = false ;
		if (error || (cache && b == null)) return ;
		if (!Kisekae.isMediaInstalled()) { error = true ; return ; }

		if ("".equals(getPath())) return ;
      long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
      if (!OptionsDialog.getCacheAudio() && zip != null) 
      {
         try
         {
            zip.connect() ;
            if (!zip.isOpen()) zip.open(); 
         }
         catch (IOException e)
         {
            error = true ;
            PrintLn.println("[" + time + "] AudioMedia: " + getName() + " Unable to open " + zip + " on initialization");
         }
      }
		if (OptionsDialog.getDebugSound())
			PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount+1 + "]" + " Initialization request.") ;
	}



	// Audio player open. 	Media sources are opened when initialized.

	void open()
   {
      Player player = null ;
		if (!Kisekae.isMediaInstalled()) { error = true ; return ; }
		if (!OptionsDialog.getSoundOn()) return ;
      long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
      setOpenTime(time) ;
      setCloseTime(0) ;
		if (OptionsDialog.getDebugSound())
			PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " Open request.") ;

      if (opened)
      {
         if (currentsound instanceof Player)
         {
            player = (Player) currentsound ;
  	  			int playerstate = player.getState() ;
   			if (playerstate >= Player.Realized)
               player.setMediaTime(new Time(0)) ;
            time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " player reset to start") ;
            return ;
         }
      }

		// Create a JMF data source for the media file.

		try
		{
			if (cache && b != null)
			{
				ds = new MediaDataSource(getContentType(file),b,b.length,getName()) ;
				ds.connect() ;
			}
			else
			{
				ds = new MediaDataSource(getContentType(file),ze) ;
				ds.connect() ;
			}
		}
		catch (Exception e)
		{
			ds = null ;
			error = true ;
			showError("AudioMedia: Unable to establish data source for " + file) ;
			if (!(e instanceof KissException)) e.printStackTrace();
		}

		// Create an instance of a player for this data source.

		if (ds != null)
		{
      	Manager.setHint(Manager.LIGHTWEIGHT_RENDERER, new Boolean(true)) ;
			try { player = Manager.createPlayer(ds) ; }
			catch (Throwable e)
			{
				ds = null ;
				error = true ;
				showError("AudioMedia: Unable to create media player for " + file) ;
				e.printStackTrace();
			}
		}

		// Initialize the player.

		if (!error)
		{
         loaded = true ;
         opened = true ;
      	currentsound = player ;
         controller = new AudioListener() ;
			player.addControllerListener((ControllerListener) controller) ;
		}
   }


   // Method to add a listener to our player object.


   void addListener(Object ce)
   {
   	listener = ce ;
		if (ce instanceof ControllerListener && currentsound instanceof Player)
        	((Player) currentsound).addControllerListener((ControllerListener) ce) ;
	}



   // Method to remove a listener from our player object.


   void removeListener(Object ce)
   {
		if (ce instanceof ControllerListener && currentsound instanceof Player)
        	((Player) currentsound).removeControllerListener((ControllerListener) ce) ;
	}



	// Method to play the audio file.   This method starts the audio from
	// the beginning. For JMF players, if the player is currently running
	// we stop it.  This routine is reinvoked when the stop event is recognized.

	void play()
	{
      long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
		if (!Kisekae.isMediaInstalled()) { error = true ; return ; }
		if (!OptionsDialog.getSoundOn()) return ;
         
      // Wait for completion if we are already stopping.
      
      if (this.isStopping()) 
      {
         synchronized(waithold) 
         {            
            try 
            { 
         		if (OptionsDialog.getDebugSound())
         		   PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " Waiting for stop to complete") ;
               waithold.wait(500) ; 
            }
            catch (InterruptedException e) { }  
            setStopping(false) ;
            time = System.currentTimeMillis() - Configuration.getTimestamp() ;
       		if (OptionsDialog.getDebugSound())
       		   PrintLn.println("[" + time + "] AudioMedia: " + getName()  + " [" + playcount + "]" + " Resuming play request") ;
         }
      }
      
		try
		{
         playcount++ ;
			if (format) throw new KissException("unknown media format") ;
			if (error) throw new KissException("object in error") ;
         
         // Open the audio file.  This will reset the audio position if the
         // file has been previously opened, or establish the appropriate 
         // player if unopened.
         
         open() ;
			if (error) throw new KissException("audio object in error on open") ;
         time = System.currentTimeMillis() - Configuration.getTimestamp() ;

         lock.lock() ;
			try { 
            if (!players.contains(me)) 
            {
          		if (OptionsDialog.getDebugSound())
          		   PrintLn.println("[" + time + "] AudioMedia: " + getName()  + " adding myself to players") ;
               players.addElement(me) ;
            } 
         }
         finally { lock.unlock() ; }

			// For JMF sound, identify the player state for debug output.

   		if (!(currentsound instanceof Player)) return ;
         Player player = (Player) currentsound ;

 			String state = "Unknown" ;
  			int playerstate = player.getState() ;
  			if (playerstate == Player.Prefetched)
            state = "Prefetched" ;
  			if (playerstate == Player.Prefetching)
            state = "Prefetching" ;
         if (playerstate == Player.Realized)
            state = "Realized" ;
 			if (playerstate == Player.Realizing)
            state = "Realizing" ;
         if (playerstate == Player.Started)
            state = "Started" ;
 			if (playerstate == Player.Unrealized)
            state = "Unrealized" ;
         if (OptionsDialog.getDebugSound())
            PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " Play request, current state is " + state) ;
         
  			// Start the player.

  			restart = false ;
  			if (player != null)
  			{
  				if (playerstate >= Player.Started)
  				{
  					restart = true ;
  					player.stop() ;
  					return ;
  				}

  				// Player is stopped.  It could be in the unrealized or
  				// prefetched state.  Start it from the beginning.

  				if (playerstate >= Player.Realized)
  					player.setMediaTime(new Time(0)) ;
            setStartTime(time) ;      
            setStopTime(0) ;      
  				player.start() ;
         }
		}

		catch (Exception e)
		{
			showError("AudioMedia: " + getName() + " start fault " + e.getMessage()) ;
			if (!(e instanceof KissException)) e.printStackTrace();
		}
	}


	// Method to stop this audio media from playing.  The audio paramater
   // must equal this sound.  If called with a configuration then this
	// sound must be associated with the configuration.  The type parameter 
   // filters by sound, music, or mediaplayer classes.

	void stopMedia(Configuration c, Audio a, String type)
	{
      if (a != null && !this.equals(a)) return ;

      // Check for configuration archive file agreement.  If the object
      // configuration reference equals the specified configuration
      // reference the the audio object was established at configuration
      // load time.  This would exclude media player objects.

      if (c != null)
      {
         Object o1 = c.cid ;
         Object o2 = cid ;
         if (o1 != null && !(o1.equals(o2))) return ;
      }

      // Check for type agreement.

      if (type != null)
      {  
         String audiotype = getType() ;
         if (audiotype != null && !type.equals(audiotype)) return ;
      }
      
      // Run the sound stop activity in a separate thread so as to not
      // interfere with the event handler FKissAction processing.  
      
      Runnable runner = new Runnable()
      { public void run() { stop1(c,a,type) ; } } ;
      Thread runthread = new Thread(runner) ;
      runthread.setName("AudioMedia " + getName() + " stop");
      runthread.start() ;
   }
   
   void stop1(Configuration c, Audio a, String type)
   {
      lock.lock() ;
      try
      {
         stopping = true ;
         long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
         if (OptionsDialog.getDebugSound())
            PrintLn.println("[" + time + "] AudioMedia: " + getName() + " Stop request.") ;

         // Shut the player down.

         if (currentsound instanceof Player)
         {
            Player player = (Player) currentsound ;
            try
            {
               if (player.getState() >= Player.Started)
               {
                  player.stop() ;
               }
            }
            catch (Exception e)
            {
               PrintLn.println("Audio player stop fault.");
               if (!(e instanceof KissException)) e.printStackTrace();
            }
         }

         // Remove the player from our active list.

         setRepeat(0) ;
         setType(null) ;
    		if (OptionsDialog.getDebugSound())
     		   PrintLn.println("[" + time + "] AudioMedia: " + getName()  + " removing from players") ;
         players.remove(this) ;
      }
      finally 
      {
         lock.unlock(); 
      }
	}


	// Method to close down our audio player.

	void close()
	{
      long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
      setCloseTime(time) ;
      if (OptionsDialog.getDebugSound())
         PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " Close request.") ;
      if (!OptionsDialog.getCacheAudio() && zip != null)
      {
         if (OptionsDialog.getDebugSound())
         	PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " call zip.disconnect()") ;
            zip.disconnect() ;
      }
		if (currentsound == null) return ;

		// Look for our player in the active play list.  If we find it,
		// remove it and close the player down.

      lock.lock() ;
      try
      {
         players.removeElement(me) ;
         removeListener(listener) ;
      }
      finally { lock.unlock() ; }

		// Close the player.

		try
		{
         if (currentsound instanceof Player)
         {
         	Player player = (Player) currentsound ;
				if (controller != null)
		        	player.removeControllerListener((ControllerListener) controller) ;
				if (Kisekae.isMediaInstalled())
	         {
					if (!error && player.getState() >= Player.Realized)
					{
						player.stop() ;
						player.close() ;
                  if (player.getState() < Player.Started)
                  {
  							player.deallocate() ;
                  }
					}
					if (ds != null)
               {
                  ds.disconnect() ;
               }
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               if (OptionsDialog.getDebugSound())
                  PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " Media player closed.") ;
            }
			}
		}
		catch (Exception e)
		{
			showError("AudioMedia: " + getPath() + " close fault.") ;
			if (!(e instanceof KissException)) e.printStackTrace();
      }

		// Release critical resources.

      flush() ;
	}

   
   // Release critical resources.

   void flush()
   {
      super.flush() ;
		ds = null ;
		loaded = false ;
      opened = false ;
      started = false ;
      hascallback = false ;
      callback = null ;
      controller = null ;
      listener = null ;
      currentsound = null ;
	}

	// Method to determine the media content type.

	Object getContentType(String filename)
	{
		int i = filename.lastIndexOf(".");
		String ext = (i > 0) ? filename.substring(i+1).toLowerCase() : "" ;
		String ct = "" ;
		if (ext.equals("mid") || ext.equals("midi"))
			ct = "audio/midi" ;
		else if (ext.equals("rmf"))
			ct = "audio/rmf" ;
		else if (ext.equals("gsm"))
			ct = "audio/x_gsm" ;
		else if (ext.equals("aiff") || ext.equals("aif"))
			ct = "audio/x_aiff" ;
		else if (ext.equals("mp2"))
			ct = "audio/x-mpegaudio" ;
		else if (ext.equals("mp3"))
			ct = "audio/mpeg" ;
		else if (ext.equals("wav"))
			ct = "audio/x-wav" ;
		else if (ext.equals("au"))
			ct = "audio/basic" ;
		ct = ContentDescriptor.mimeTypeToPackageName(ct);
		cd = new ContentDescriptor(ct);
		return cd;
	}


	// Inner class to catch Controller events.

	class AudioListener implements ControllerListener
	{
		public void controllerUpdate(ControllerEvent ce)
		{
			// RealizeCompleteEvent occurs after a realize() call.

			if (ce instanceof RealizeCompleteEvent)
			{
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " RealizeCompleteEvent") ;
				realized = true ;

	         // Try to increase the buffer size to stop choppy midi sound.
            // (This doesn't seem to work for midi, but does for other types.)

//				BufferControl bc = (javax.media.control.BufferControl)
//	         	player.getControl("javax.media.control.BufferControl") ;
//				if (bc != null)
//				{
//            	bc.setBufferLength(1000000) ;
//					PrintLn.println("AudioMedia: " + me.toString() + " buffer size set") ;
//				}
			}

			// PrefetchCompleteEvent is generated when the player has finished
			// prefetching enough data to fill its internal buffers and is ready
			// to start playing.

			else if (ce instanceof PrefetchCompleteEvent)
			{
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " PrefetchCompleteEvent") ;
			}

			// EndOfMediaEvent occurs when the media file has played till the end.
			// The player is now in the stopped state.

			else if (ce instanceof EndOfMediaEvent)
			{
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " EndOfMediaEvent") ;

				// Start the player in a new thread as player initiation can take
				// time.  This frees the Player thread.

				if (repeat)
				{
					if (repeatcount > 0) repeatcount-- ;
					repeat = (repeatcount != 0) ;
               if (repeatcount > 0)
               {
                  time = System.currentTimeMillis() - Configuration.getTimestamp() ;
                  if (OptionsDialog.getDebugSound())
                     PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " repeat " + repeatcount) ;
                  Runnable runner = new Runnable()
                  { public void run() { play() ; } } ;
                  javax.swing.SwingUtilities.invokeLater(runner) ;
               }
				}
				else
            {
					doCallback() ;
            	started = false ;
               stopping = false ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               setStopTime(time) ;   
            }
			}
         
			// If at any point the Player encountered an error - possibly in the
			// data stream and it could not recover from the error, it generates
			// a ControllerErrorEvent

			else if (ce instanceof ControllerErrorEvent)
			{
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " ControllerErrorEvent " + ce.toString()) ;
				showError("AudioMedia: Unable to play media file " + file) ;
            lock.lock() ;
            try { players.removeElement(me) ; }
            finally { lock.unlock() ; }
				error = true ;
			}

			// The ControllerClosedEvent occurs when a player is closed.

			else if (ce instanceof ControllerClosedEvent)
			{
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " ControllerClosedEvent") ;
            lock.lock() ;
            try { players.removeElement(me) ; }
            finally { lock.unlock() ; }
            synchronized (waithold)
            {
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
         		if (OptionsDialog.getDebugSound())
         		   PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " Notify stop is complete") ;
               waithold.notify() ;
            }
            stopping = false ;
            loaded = false ;
			}

			// DurationUpdateEvent occurs when the player's duration changes or is
			// updated for the first time

			else if (ce instanceof DurationUpdateEvent)
			{
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " DurationUpdateEvent") ;
			}

			// Caching control.

			else if (ce instanceof CachingControlEvent)
			{
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " CachingControlEvent") ;
			}

			else if (ce instanceof StartEvent)
			{
				doCallstart() ;
				started = true ;
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " StartEvent") ;

            // Fire any generic mediastart() events.
      
            MainFrame mf = Kisekae.getMainFrame() ;
            Configuration config = (mf != null) ? mf.getConfig() : null ;
            PanelFrame panel = (mf != null) ? mf.getPanel() : null ;
            EventHandler handler = (config != null) ? config.getEventHandler() : null ;
      		Vector v = (handler != null) ? handler.getEvent("mediastart") : null ;
            if (v != null)
            {
               Vector mediaevents = new Vector() ;
               for (int i = 0; i < v.size(); i++)
               {
                  FKissEvent e = (FKissEvent) v.elementAt(i) ;
                  Vector params = e.getParameters() ;
                  if (params == null || params.size() > 0) continue ;
                  mediaevents.add(e) ;
               }
               if (mediaevents.size() > 0)
            		EventHandler.fireEvents(mediaevents,panel,Thread.currentThread(),null) ;
            }
			}

			else if (ce instanceof StopEvent)
			{
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " StopEvent") ;

            synchronized (waithold)
            {
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
         		if (OptionsDialog.getDebugSound())
         		   PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " Notify stop is complete") ;
               waithold.notify() ;
            }
            stopping = false ;
            setStopTime(time) ;      

				// The repeat count is a count of the number of times to play the 
            // sound.  If zero this is a request to stop playing the sound.

				if (repeat || restart)
				{
					if (repeatcount > 0) repeatcount-- ;
					repeat = (repeatcount != 0) ;
					if (repeatcount > 0 || restart) 
               {
                  time = System.currentTimeMillis() - Configuration.getTimestamp() ;
                  if (OptionsDialog.getDebugSound())
                     PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " Restart") ;
   					Runnable runner = new Runnable()
   					{ public void run() { play() ; } } ;
        				javax.swing.SwingUtilities.invokeLater(runner) ;
   				}
            }
				if (!repeat || repeatcount == 0)
            {
               doCallback() ;
               started = false ;
            }

            // Fire any generic mediastop() events.  
      
            MainFrame mf = Kisekae.getMainFrame() ;
            Configuration config = (mf != null) ? mf.getConfig() : null ;
            PanelFrame panel = (mf != null) ? mf.getPanel() : null ;
            EventHandler handler = (config != null) ? config.getEventHandler() : null ;
      		Vector v = (handler != null) ? handler.getEvent("mediastop") : null ;
            if (v != null)
            {
               Vector mediaevents = new Vector() ;
               for (int i = 0; i < v.size(); i++)
               {
                  FKissEvent e = (FKissEvent) v.elementAt(i) ;
                  Vector params = e.getParameters() ;
                  if (params == null || params.size() > 0) continue ;
                  mediaevents.add(e) ;
               }
               if (mediaevents.size() > 0)
            		EventHandler.fireEvents(mediaevents,panel,Thread.currentThread(),null) ;
            }
	      }

			else if (ce instanceof MediaTimeSetEvent)
	      {
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " MediaTimeEvent") ;
	      }

			else if (ce instanceof TransitionEvent)
	      {
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " TransitionEvent") ;
	      }
			else if (ce instanceof RateChangeEvent)
	      {
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("AudioMedia: " + "[" + time + "] " + getName() + " [" + playcount + "]" + " RateChangeEvent") ;
	      }

			else if (ce instanceof StopTimeChangeEvent)
	      {
            long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioMedia: " + getName() + " [" + playcount + "]" + " StopTimeChangeEvent") ;
	      }
		}
   }
}



