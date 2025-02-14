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


import java.io.* ;
import java.awt.event.* ;
import java.util.Hashtable ;
import java.util.Vector ;
import java.util.Enumeration ;
import java.net.URL ;
import javax.swing.* ;
import javax.sound.sampled.* ;
import java.util.concurrent.locks.ReentrantLock ;

import javax.sound.midi.* ;


class AudioSound extends Audio
{
	private Object cd = null;					// The audio content descriptor
	private MediaDataSource ds = null ;		// The audio data source
	protected Object currentsound ; 			// Sequencer, player or clip
   protected Object listener = null ;		// Last listener added
   protected Object metalistener = null ;	// Internal sequencer listener
   protected Object linelistener = null ;	// Internal line listener
   private final Object waithold = new Object() ; 

	// Constructor for when we do not have a configuration.

	public AudioSound(ArchiveFile zip, String file)
	{
		me = this ;
		setUniqueID(new Integer(this.hashCode())) ;
		init(zip,file,null) ;
	}

	// Constructor

	public AudioSound(ArchiveFile zip, String file, Configuration ref)
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

   // Method to return the last listener object added.
   
   // Method to return an input stream for this audio object.
   // If we have already established an input stream then return it.
   
   InputStream getInputStream()
   {
      if (is != null) return is ;
      return super.getInputStream() ;
   }


   Object getListener() { return listener ; }


	// Method to return the content type.

	String getContentType()
	{
		String s = (String) getContentType(getName()) ;
		if (s == null) return super.getContentType() ;
		return s ;
	}

	// Sequencer type.

	boolean isSequencer() { return (currentsound instanceof Sequencer) ; }

	// Clip type.

	boolean isClip() { return (currentsound instanceof Clip) ; }

	// Return an indication if the medium is Java sound.

	boolean isJavaSound() { return true ; }

	// Return the object state.

	String getState()
	{
		String state = super.getState() ;
		if (isSequencer())
		{
			Sequencer p = (Sequencer) currentsound ;
			if (p.isOpen())
            state = Kisekae.getCaptions().getString("MediaPrefetchedState") ;
			if (p.isRecording())
            state = Kisekae.getCaptions().getString("MediaRecordingState") ;
			if (p.isRunning())
            state = Kisekae.getCaptions().getString("MediaStartedState") ;
		}
		if (isClip())
		{
			Clip p = (Clip) currentsound ;
			if (p.isOpen())
            state = Kisekae.getCaptions().getString("MediaPrefetchedState") ;
			if (p.isRunning())
            state = Kisekae.getCaptions().getString("MediaStartedState") ;
		}
		return state ;
	}

	// Return the object position.

	int getPosition()
	{
		int position = super.getPosition() ;
		if (isSequencer())
		{
			Sequencer p = (Sequencer) currentsound ;
			position = (int) (p.getMicrosecondPosition() / 1000000.0) ;
		}
		if (isClip())
		{
			Clip p = (Clip) currentsound ;
			position = (int) (p.getMicrosecondPosition() / 1000000.0) ;
		}
		return position ;
	}

	// Return the object duration.

	int getDuration()
	{
		int duration = super.getDuration() ;
		if (isSequencer())
		{
			Sequencer p = (Sequencer) currentsound ;
			duration = (int) (p.getMicrosecondLength() / 1000000.0) ;
		}
		if (isClip())
		{
			Clip p = (Clip) currentsound ;
			duration = (int) (p.getMicrosecondLength() / 1000000.0) ;
		}
      if (duration == 0 && (framesize == 0 || framerate == 0))
      {
        	InputStream is = getInputStream() ;
         if (is != null) 
         {
            try 
            {
               AudioInputStream ais = AudioSystem.getAudioInputStream(is) ;
               audiofmt = (ais != null) ? ais.getFormat() : null ;
               framesize = (audiofmt != null) ? audiofmt.getFrameSize() : 0 ;
               framerate = (audiofmt != null) ? audiofmt.getFrameRate() : 0 ;
            }
            catch (Exception e) { }   
         }      
      }
      if (duration == 0 && framesize > 0 && framerate > 0)
      {
         duration = (int) (getBytes() / (framesize * framerate)) ;         
      }
		return duration ;
	}



	// Object loading methods
	// ----------------------


	// Audio player initialization.

	void init()
	{
      playcount = 0 ;
		started = false ;
      stopping = false ;
		if (error || (cache && b == null)) return ;
		if ("".equals(getPath())) return ;
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
            PrintLn.println("AudioSound: " + getName() + " Unable to open " + zip + " on initialization");
         }
      }
		if (!error && OptionsDialog.getDebugSound())
			PrintLn.println("AudioSound: " + getName() + " Initialization request, cached = " + cache) ;
   }



	// Audio player open. 	Create a sound data source from the memory array.

	void open()
	{
		if (error || (cache && b == null)) return ;
		if ("".equals(getPath())) return ;
		if (!OptionsDialog.getJavaSound()) return ;
      long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
		if (OptionsDialog.getDebugSound())
			PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Open request.") ;

		// Midi files do not play properly using JMF when the application is
		// loaded from a jar file.  We use Java Sound for playback if the Java
		// Sound option is set.

   	try
   	{
         if (opened)
         {
            if (currentsound instanceof Clip)
            {
               Clip clip = (Clip) currentsound ;
               clip.setMicrosecondPosition(0) ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               if (OptionsDialog.getDebugSound())
                  PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " clip reset to start") ;
               return ;
            }
            if (currentsound instanceof Sequencer)
            {
               Sequencer sequencer = (Sequencer) currentsound ;
               sequencer.setMicrosecondPosition(0) ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               if (OptionsDialog.getDebugSound())
                  PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " sequencer reset to start") ;
               return ;
            }
         }
         
         // Actually open the sound file. Get an input stream and create
         // a player.
         
         setOpenTime(time) ;
         setCloseTime(0) ;
       	is = getInputStream() ;
         if (is == null) return ;
         try
         {
            if (!ArchiveFile.isMP3Sound(getName()))
                currentsound = AudioSystem.getAudioInputStream(is) ;
            else if (ArchiveFile.isMP3Sound(getName()) && Kisekae.isMP3Installed())
                currentsound = is ;     
            else
               throw new Exception("unknown sound type") ;
            time = System.currentTimeMillis() - Configuration.getTimestamp() ;
            if (OptionsDialog.getDebugSound())
               PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " is a recognized stream") ;
         }
         catch (Exception ex)
   		{  // load midi & rmf as inputstreams for now.
            if (OptionsDialog.getDebugSound())
            {
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
 					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " " + ex) ;
 					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " attempt recovery, assuming sequencer type") ;
            }
	         currentsound = new BufferedInputStream(is,4096) ;
         }

         // Decode the sound type.  WAV and AU and such.

			if (currentsound instanceof AudioInputStream)
         {
				try
            {
					AudioInputStream stream = (AudioInputStream) currentsound;
					AudioFormat format = stream.getFormat() ;
               framesize = format.getFrameSize() ;
               framerate = format.getFrameRate() ;

					// we can't yet open the device for ALAW/ULAW playback,
					// convert ALAW/ULAW to PCM

					if ((format.getEncoding() == AudioFormat.Encoding.ULAW) ||
						(format.getEncoding() == AudioFormat.Encoding.ALAW)) 
					{
						AudioFormat tmp = new AudioFormat(
   					AudioFormat.Encoding.PCM_SIGNED,
						format.getSampleRate(),
						format.getSampleSizeInBits() * 2,
						format.getChannels(),
						format.getFrameSize() * 2,
						format.getFrameRate(),
						true);
                  stream = AudioSystem.getAudioInputStream(tmp, stream);
                  format = tmp;
               }

               // Get the data line information.

					DataLine.Info info = new DataLine.Info(
					   Clip.class, stream.getFormat(),
					   ((int) stream.getFrameLength() * format.getFrameSize())) ;

				   // Create a sound clip.

				   Clip clip = (Clip) AudioSystem.getLine(info) ;
               linelistener = new ClipListener() ;
				   clip.addLineListener((LineListener) linelistener);
				   clip.open(stream);
				   currentsound = clip;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
				   if (OptionsDialog.getDebugSound())
					   PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sound clip opened") ;
               loaded = true ;
               return ;
				}
            
            // LineUnavailableException occurs when the sound cannot play.
            // This can happen on Linux or others if issues with the audio.
            
            catch (LineUnavailableException e)
            {
               currentsound = null ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
				   if (OptionsDialog.getDebugSound())
                  PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " " + e.toString()) ;
            }
            catch (Exception ex)
            {
              	error = true ;
					currentsound = null ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
					showError("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sound clip stream exception, " + ex.toString()) ;
					ex.printStackTrace() ;
				}
			}

         // Decode the sound type.  MP3 files.

         else if (currentsound instanceof InputStream  && ArchiveFile.isMP3Sound(getName()))
         {
				try
            {
					InputStream stream = (InputStream) currentsound;
				   Mp3Clip clip = new Mp3Clip(this,stream) ;
               linelistener = new ClipListener() ;
			      clip.addLineListener((LineListener) linelistener);
               clip.open() ;  
               currentsound = clip ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
				   if (OptionsDialog.getDebugSound())
					   PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " MP3 player opened") ;
               loaded = true ;
               return ;
				}
            catch (Exception ex)
            {
              	error = true ;
					currentsound = null ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
					showError("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sound clip stream exception, " + ex.toString()) ;
				}
			}

         // We do not have a known Java sound.  Try MIDI or RMF.  If we
			// fail on this and JMF is installed, try to allocate a JMF player.
         // The JMF recovery must occur by the caller (FKissAction) as this 
         // AudioSound object will be set with an error state.  

			else if (currentsound instanceof BufferedInputStream)
         {
				try
            {
					Sequencer sequencer = MidiSystem.getSequencer() ;
					sequencer.open() ;
					sequencer.setSequence((BufferedInputStream) currentsound) ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
					if (OptionsDialog.getDebugSound())
						PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sequencer opened") ;

               currentsound = sequencer ;
               metalistener = new SequencerListener() ;
					sequencer.addMetaEventListener((MetaEventListener) metalistener) ;
               loaded = true ;
					return ;
				}

				catch (Exception e)
				{
		        	error = true ;
               format = true ;
					currentsound = null;
              	if (!Kisekae.isMediaInstalled())
               {
                  time = System.currentTimeMillis() - Configuration.getTimestamp() ;
        				showError("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " not a recognized audio format") ;
               }
               else
               {
  						if (OptionsDialog.getDebugSound())
                  {
                     time = System.currentTimeMillis() - Configuration.getTimestamp() ;
                     PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " " + e.toString()) ;
  							PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " attempt JMF recovery") ;
                  }
               }
				}
         }
		}
      catch (Exception e)
      {
        	error = true ;
			currentsound = null;
         showError("AudioSound: " + getName() + " Sound stream exception, " + e.toString()) ;
        	e.printStackTrace() ;
      }
	}



	// Method to add a listener to our player object.

	void addListener(Object ce)
	{
   	listener = ce ;
		if (ce instanceof MetaEventListener && currentsound instanceof Sequencer)
        	((Sequencer) currentsound).addMetaEventListener((MetaEventListener) ce) ;
		if (ce instanceof LineListener && currentsound instanceof Clip)
       	((Clip) currentsound).addLineListener((LineListener) ce) ;
   }



   // Method to remove a listener from our player object.

   void removeListener(Object ce)
   {
		if (ce instanceof MetaEventListener && currentsound instanceof Sequencer)
        	((Sequencer) currentsound).removeMetaEventListener((MetaEventListener) ce) ;
		if (ce instanceof LineListener && currentsound instanceof Clip)
       	((Clip) currentsound).removeLineListener((LineListener) ce) ;
	}



	// Method to play the audio file.   This method starts the audio from
	// from the beginning because open() request resets the audio position.
   // For JMF players, if the player is currently running we stop it.  
   // This routine can be reinvoked when the stop event is recognized for
   // repeated playback.
   
   // Note: Audio did not always play with Java 1.5 when we reset the media 
   // position so we reopen our audio sound to force a new initialization.
   // Sept 2008 - a close and open can take an excessive time for animation.

	void play()
	{
      if (!loaded && OptionsDialog.getCacheAudio()) return ;
		if (!OptionsDialog.getSoundOn()) return ;

      // Run the sound playback activity in a separate thread so as to not
      // interfere with the event handler FKissAction processing.  

      playcount++ ;
      Runnable runner = new Runnable()
      { public void run() { play1() ; } } ;
      Thread runthread = new Thread(runner) ;
      runthread.setName("AudioSound play " + getName());
      runthread.start() ;
   }
   
	void play1()
   {
      long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
		if (OptionsDialog.getDebugSound())
		   PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Play request.") ;

      setStartTime(time) ;      
      setStopTime(0) ;    
      
      // Wait for completion if we are already stopping.
      
      if (this.isStopping()) 
      {
         synchronized(waithold) 
         {            
            try 
            { 
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
         		if (OptionsDialog.getDebugSound())
         		   PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Waiting for stop to complete") ;
               waithold.wait(500) ; 
            }
            catch (InterruptedException e) { }  
            setStopping(false) ;
            time = System.currentTimeMillis() - Configuration.getTimestamp() ;
       		if (OptionsDialog.getDebugSound())
       		   PrintLn.println("[" + time + "] AudioSound: " + getName()  + " [" + playcount + "]" + " Resuming play request") ;
         }
      }

      // Now open the audio and start playing the sound.  If the playback
      // fails try Java Media Framework.
      
      lock.lock() ;
      try
		{
			if (format) throw new KissException("unknown media format") ;
			if (error) throw new KissException("audio object in error") ;
			if (!players.contains(me)) 
         {
       		if (OptionsDialog.getDebugSound())
       		   PrintLn.println("[" + time + "] AudioSound: " + getName()  + " adding myself to players") ;
            players.addElement(me) ;
         }
         
         // Open the audio file.  This will reset the audio position if the
         // file has been previously opened, or establish the appropriate 
         // clip or sequencer if unopened.
         
         open() ;
			if (error) throw new KissException("audio object in error on open") ;

	      // Midi files may not play properly if a soundbank is not installed.
         // We use Java Sound playback.  The sequencer events for start and
         // stop do not fire when the position is changed so we explicitly
         // check for mediaplayer callback events.

         if (currentsound instanceof Sequencer)
         {
            time = System.currentTimeMillis() - Configuration.getTimestamp() ;
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sequencer started") ;
				Sequencer sequencer = (Sequencer) currentsound ;
            doCallback() ;
            started = false ;
            if (isLooping() || (getBackground() && OptionsDialog.getAutoMediaLoop()))
            {
               sequencer.setLoopCount(Sequencer.LOOP_CONTINUOUSLY);
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               if (OptionsDialog.getDebugSound())
                  PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sequencer is looping.") ;
            }
         	sequencer.start() ;
            doCallstart() ;
         }

         // Java sound can be standard media, too.  In this case resetting the
         // media position appears to fire stop and start events.

			if (currentsound instanceof Clip)
         {
            time = System.currentTimeMillis() - Configuration.getTimestamp() ;
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Clip started") ;
				Clip clip = (Clip) currentsound ;
            started = false ;
            if (isLooping() || (getBackground() && OptionsDialog.getAutoMediaLoop()))
            {
               clip.loop(Clip.LOOP_CONTINUOUSLY);
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               if (OptionsDialog.getDebugSound())
                  PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Clip is looping.") ;
            }
            
				clip.start() ;
            if (clip instanceof Mp3Clip)
               cd = ((Mp3Clip) clip).getContentDescriptor() ;
            else
            {
               AudioFormat fmt = clip.getFormat() ;
               if (fmt != null) cd = fmt.toString() ;
            }
			}
		}

      catch (KissException e) 
      { 
         // The play request may fail.  For audio sound files we can
         // try and recover using Java Media Framework.  The new AudioMedia
         // object is a load copy of the original object and it replaces the
         // original AudioSound object.

         if (Kisekae.isMediaInstalled())
         {
            Object o = Audio.getByKey(Audio.getKeyTable(),cid,getPath().toUpperCase()) ;

            // Have we replaced this object previously?

            if (o == this)
            {
               MainFrame mf = Kisekae.getMainFrame() ;
               Configuration config = (mf != null) ? mf.getConfig() : null ;
               KissObject kiss = new AudioMedia(getZipFile(),getPath()) ;
               Audio a1 = (Audio) kiss ;
               a1.setIdentifier(getIdentifier()) ;
               a1.setRelativeName(getRelativeName()) ;
               a1.setRepeat(getRepeat()) ;
               a1.setType(getType()) ;
               a1.setID(getID()) ;
               a1.load((config != null) ? config.getIncludeFiles() : null) ;
               Audio.removeKey(Audio.getKeyTable(),cid,getPath().toUpperCase()) ;
               a1.setKey(a1.getKeyTable(),cid,a1.getPath().toUpperCase()) ;
               a1.setCopy(false) ;
               Enumeration enum1 = getEvents() ;
               while (enum1 != null && enum1.hasMoreElements())
                  a1.addEvent((Vector) enum1.nextElement()) ;
               Vector sounds = (config != null) ? config.getSounds() : null ;
               int n = (sounds != null) ? sounds.indexOf(this) : -1 ;
               if (n >= 0) sounds.setElementAt(a1,n) ;
      			boolean b = players.remove(o) ;
          		if (OptionsDialog.getDebugSound() && b)
                  PrintLn.println("[" + time + "] AudioSound: " + getName() + " removed from player list") ;
          		if (OptionsDialog.getDebugSound() && !b)
                  PrintLn.println("[" + time + "] AudioSound: " + getName() + " not in player list for removal") ;
               a1.init() ;
               o = a1 ;
               
               if (config != null)
               {
                  AudioTimer timer = config.getAudioTimer() ;
                  if (timer != null)
                  {
                     timer.suspendTimer(true) ;
                     timer.updateAudio(a1) ;
                     timer.resumeTimer(true) ;
                  }
               }
            }
            if (o instanceof AudioMedia)
               ((Audio) o).play() ;
         }
      }
		catch (Exception e)
		{
 			showError("AudioSound: " + getName() + " start fault " + e.getMessage()) ;
         e.printStackTrace();
		}
      finally { lock.unlock() ; }
	}


	// Method to stop this audio sound from playing.  The audio paramater
   // must equal this sound.  If called with a configuration then this
	// sound must be associated with the configuration.  The type parameter 
   // filters by sound, music, or mediaplayer classes.

	void stopSound(Configuration c, Audio a, String type)
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
      runthread.setName("AudioSound " + getName() + " stop");
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
            PrintLn.println("[" + time + "] AudioSound: " + getName() + " Stop request.") ;

         // Shut the sequencer down.  We have to do a manual callback request
         // as the sequencer event listener does not recognize stop events.

         if (currentsound instanceof Sequencer)
         {
            try
            {
               Sequencer sequencer = (Sequencer) currentsound ;
               if (sequencer.isRunning()) 
               {
                  time = System.currentTimeMillis() - Configuration.getTimestamp() ;
                  if (OptionsDialog.getDebugSound())
                     PrintLn.println("[" + time + "] AudioSound: " + getName() + " Sequencer stopped") ;
                  sequencer.stop() ;
                  doCallback() ;
               }
            }
            catch (Exception e)
            {
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               PrintLn.println("[" + time + "] AudioSound: " + getName() +  "Audio sequencer stop fault.");
               if (!(e instanceof KissException)) e.printStackTrace();
            }
         }

         // Shut the sound clip down.  The sound clip event listener will
         // fire any callback events when the clip stops.  Clip resources
         // and input stream are freed on a periodic basis by the AudioTimer 
         // activity if audio is not cached.

         if (currentsound instanceof Clip)
         {
            try
            {
               Clip clip = (Clip) currentsound ;
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               clip.stop() ;
               if (OptionsDialog.getDebugSound())
                  PrintLn.println("[" + time + "] AudioSound: " + getName() + " Sound clip stopped") ;
            }
            catch (Exception e)
            {
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
               PrintLn.println("[" + time + "] AudioSound: " + getName() + "Audio clip stop fault.");
               if (!(e instanceof KissException)) e.printStackTrace();
            }
         }

         // Remove the player from our active list.

         setRepeat(0) ;
         setType(null) ;
         boolean b = players.remove(this) ;
         if (OptionsDialog.getDebugSound() && b)
            PrintLn.println("[" + time + "] AudioSound: " + getName() + " removed from player list") ;
     		if (OptionsDialog.getDebugSound() && !b)
            PrintLn.println("[" + time + "] AudioSound: " + getName() + " not in player list for removal") ;
      }
      finally 
      {
         lock.unlock() ; 
      }
   }


	// Method to close down our audio player.

	void close()
   {
      long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
      setCloseTime(time) ;
      closeaudio() ;
      try 
      {
         if (is != null) 
            is.close() ;
      }
      catch (IOException e)
      {
         e.printStackTrace() ;
      }
      flush() ;
   }
   
	void closeaudio()
	{
      if (error) return ;
      long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
      if (OptionsDialog.getDebugSound())
         PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Close request.") ;
       if (!OptionsDialog.getCacheAudio() && zip != null)
      {
         if (OptionsDialog.getDebugSound())
            PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " call zip.disconnect()") ;
         zip.disconnect() ;
      }

		// Look for our player in the active play list.  If we find it,
		// remove it and close the player down.

      lock.lock() ;
      try
      {
         players.removeElement(me) ;
         removeListener(listener) ;
         removeListener(metalistener) ;
         removeListener(linelistener) ;
      }
      finally { lock.unlock() ; }

		// Close the player.

		try
		{
			if (currentsound instanceof Sequencer)
			{
            Sequencer sequencer = (Sequencer) currentsound ;
				sequencer.stop() ;
				sequencer.close() ;
            time = System.currentTimeMillis() - Configuration.getTimestamp() ;
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sequencer closed") ;
			}

			if (currentsound instanceof Clip)
			{
				Clip clip = (Clip) currentsound ;
         	clip.stop() ;
            clip.flush() ;
            clip.close() ;  // Linux reports NoLineAvailableException at times
            time = System.currentTimeMillis() - Configuration.getTimestamp() ;
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sound clip closed") ;
         }
		}
		catch (Exception e)
		{
			showError("AudioSound: " + getPath() + " close fault.") ;
			if (!(e instanceof KissException)) e.printStackTrace();
	   }
      
      started = false ;
      opened = false ;
      loaded = false ;
      currentsound = null ;
      metalistener = null ;
      linelistener = null ;
      listener = null ;
   }

   
   // Release critical resources.

   void flush()
   {
      super.flush() ;
		b = null ;
		ds = null ;
      is = null ;
		loaded = false ;
      opened = false ;
      started = false ;
      stopping = false ;
      hascallback = false ;
      callback = null ;
      listener = null ;
      metalistener = null ;
      linelistener = null ;
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
		return ct ;
	}

	// Inner class to catch sound clip events.

	class ClipListener implements LineListener
	{
		public void update(LineEvent event)
		{
			// Stop events occurs when the media file has played till the end.

         long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
			if (event.getType() == LineEvent.Type.STOP)
			{
            setStopTime(time) ;      
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " ClipStopEvent, stopping=" + stopping) ;

            synchronized (waithold)
            {
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
         		if (OptionsDialog.getDebugSound())
         		   PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Notify stop is complete") ;
               waithold.notify() ;
            }
            stopping = false ;
            setStopTime(time) ;      

				// The repeat count is a count of the number of times to play the 
            // sound.  If zero this is a request to stop playing the sound.

				if (repeat)
				{
					if (repeatcount > 0) repeatcount-- ;
					repeat = (repeatcount != 0) ;
					if (repeatcount > 0) 
               {
                  Runnable runner = new Runnable()
                  { public void run() { play() ; } } ;
                  javax.swing.SwingUtilities.invokeLater(runner) ;
   					if (OptionsDialog.getDebugSound())
                  	PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Clip repeat invoked, count = " + repeatcount) ;
               }
				}
				if (!repeat || repeatcount == 0)
            {
					doCallback() ;
            	started = false ;
            }

            // Flush the line to remove any queued data.
            
            Line line = event.getLine() ;
            if (line instanceof DataLine)
            {
               ((DataLine) line).flush() ;
               if (Kisekae.isWebswing())
               {
                  if (OptionsDialog.getDebugSound())
                     PrintLn.println("[" + time + "] AudioWebswing: " + getName() + " [" + playcount + "]" + " prepareClip close on stop event.") ;
//                  line.close();
               }
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

			// The Close Event occurs when a clip is closed.

			else if (event.getType() == LineEvent.Type.CLOSE)
			{
				opened = false ;
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " ClipCloseEvent, stopping=" + stopping) ;
            if (Kisekae.isWebswing()) return ;
            lock.lock() ;
            try 
            { 
               players.removeElement(me) ; 
               if (is != null) 
               {
                  is.close() ;
            		if (OptionsDialog.getDebugSound())
                     PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Input stream is closed") ;
               }
            }
            catch (IOException e) { }     
            finally { lock.unlock() ; }
            is = null ;
            synchronized (waithold)
            {
               time = System.currentTimeMillis() - Configuration.getTimestamp() ;
         		if (OptionsDialog.getDebugSound())
         		   PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Notify stop is complete") ;
               waithold.notify() ;
            }
            stopping = false ;
  			}

			else if (event.getType() == LineEvent.Type.START)
			{
				doCallstart() ;
				started = true ;
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " ClipStartEvent") ;

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

			else if (event.getType() == LineEvent.Type.OPEN)
			{
				opened = true ;
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " ClipOpenEvent") ;
	      }
      }
   }


	// Inner class to catch sound sequencer events.

	class SequencerListener implements MetaEventListener
	{
		public void meta(MetaMessage message)
      {
         long time = System.currentTimeMillis() - Configuration.getTimestamp() ;
         setStopTime(time) ;      
			if (message.getType() == 47)
         {  // 47 is end of track
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " MidiEndTrackEvent " + message.getType()) ;

				// Start the player in a new thread as player initiation can take
				// time.  This frees the Player thread.

				if (repeat)
				{
					if (repeatcount > 0) repeatcount-- ;
					repeat = (repeatcount != 0) ;
					if (repeatcount > 0) 
               {
                  Runnable runner = new Runnable()
                  { public void run() { play() ; } } ;
                  javax.swing.SwingUtilities.invokeLater(runner) ;
   					if (OptionsDialog.getDebugSound())
                  	PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " Sequencer repeat invoked, count = " + repeatcount) ;
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

	      // Any other meta event will signal a start event.

			else
	      {  // 00 is start of track (optional)
	        	// 02 is copyright info (optional)
	         // 03 is sequence or track name (optional)
	         // 04 is instrument name (optional)
            if (message.getType() == 2)
               copyright = new String(message.getData()) ;
				if (OptionsDialog.getDebugSound())
					PrintLn.println("[" + time + "] AudioSound: " + getName() + " [" + playcount + "]" + " MidiStartTrackEvent " + message.getType()) ;
	         if (started) return ;
				doCallstart() ;
	         started = true ;

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
      }
   }
}

