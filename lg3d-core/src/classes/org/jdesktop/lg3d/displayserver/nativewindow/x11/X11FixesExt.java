/**
 * Project Looking Glass
 *
 * $RCSfile: X11FixesExt.java,v $
 *
 * Copyright (c) 2004, Sun Microsystems, Inc., All Rights Reserved
 * Portions Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 *
 * $Revision: 1.7 $
 * $Date: 2007-04-10 23:25:09 $
 * $State: Exp $
 */
/** Copyright (c) 2004 Amir Bukhari
*
* Permission to use, copy, modify, distribute, and sell this software and its
* documentation for any purpose is hereby granted without fee, provided that
* the above copyright notice appear in all copies and that both that
* copyright notice and this permission notice appear in supporting
* documentation, and that the name of Amir Bukhari not be used in
* advertising or publicity pertaining to distribution of the software without
* specific, written prior permission.  Amir Bukhari makes no
* representations about the suitability of this software for any purpose.  It
* is provided "as is" without express or implied warranty.
*
* AMIR BUKHARI DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE,
* INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS, IN NO
* EVENT SHALL AMIR BUKHARI BE LIABLE FOR ANY SPECIAL, INDIRECT OR
* CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE,
* DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
* TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
* PERFORMANCE OF THIS SOFTWARE.
*/
package org.jdesktop.lg3d.displayserver.nativewindow.x11;

import gnu.x11.Data;
import gnu.x11.Display;
import gnu.x11.Enum;
import gnu.x11.Pixmap;
import gnu.x11.Rectangle;
import gnu.x11.Request;
import gnu.x11.Window;
import gnu.x11.XProtocolInfo;
import gnu.x11.event.Event;
import gnu.x11.extension.*;


/** 
 * this class implement the xfixes API. until now only a few function is implemented
 */
public class X11FixesExt extends Extension implements EventFactory {
  static final String [] MINOR_OPCODE_STRINGS = {
    /*************** Version 1 ******************/
    "QueryVersion",                             // 0
    "ChangeSaveSet",                            // 1
    "SelectSelectionInput",                     // 2
    "SelectCursorInput",                        // 3
    "GetCursorImage",                           // 4
    /*************** Version 2 *****************/
    "CreateRegion",                             // 5
    "CreateRegionFromBitmap",                   // 6
    "CreateRegionFromWindow",                   // 7
    "CreateRegionFromGC",                       // 8
    "CreateRegionFromPicture",                  // 9
    "DestroyRegion",                            // 10
    "SetRegion",                                // 11
    "CopyRegion",                               // 12
    "UnionRegion",                              // 13
    "IntersectRegion",                          // 14
    "SubtractRegion",                           // 15
    "InvertRegion",                             // 16
    "TranslateRegion",                          // 17
    "RegionExtents",                            // 18
    "",                                         // 19
    "SetGCClipRegion",                          // 20
    "SetWindowShapeRegion",                     // 21
    "SetPictureClipRegion",                     // 22
    "SetCursorName",                            // 23
    "GetCursorName",                            // 24
    "GetCursorImageAndName",                    // 25
    "ChangeCursor",                             // 26
    "ChangeCursorByName",                       // 27
    /*************** Version 3 ******************/
    "ExpandRegion",                             // 28
    "ExpandRegion"                              // 29
  };
  
  public static final int CLIENT_MAJOR_VERSION = 3;
  public static final int CLIENT_MINOR_VERSION = 0;


  public int server_major_version, server_minor_version;


  /**
   * 
   */
  public X11FixesExt (Display display) throws NotFoundException { 
    // XFixes defines exactly two events: SelectionNotify (first_event + 0)
    // and CursorNotify (first_event + 1). Register both so the reader thread
    // can decode them instead of dropping them as "unsupported".
    super (display, "XFIXES", MINOR_OPCODE_STRINGS, 0, 2);

    // These extension requests expect replies
    XProtocolInfo.extensionRequestExpectsReply(major_opcode, 0, 32); // QueryVersion

    // check version before any other operations
    Request request = new Request (display, major_opcode, 0, 3);
    request.write4 (CLIENT_MAJOR_VERSION);
    request.write4 (CLIENT_MINOR_VERSION);
	
    Data reply = display.read_reply (request);
    server_major_version = reply.read2 (8);
    server_minor_version = reply.read2 (10);
  }

  /** XFixes cursor-event mask bit: request SetCursorNotify events. */
  public static final int SET_CURSOR_NOTIFY_MASK = 1;

/*
typedef struct {
    CARD8   reqType;
    CARD8   xfixesReqType;   // 3
    CARD16  length B16;      // 3
    Window  window B32;
    CARD32  eventMask B32;
} xXFixesSelectCursorInputReq;
#define sz_xXFixesSelectCursorInputReq  12
*/
  /**
   * Selects delivery of XFixes cursor events on <code>window</code>. A
   * compositor calls this on the root window with
   * {@link #SET_CURSOR_NOTIFY_MASK} to be told whenever the cursor image
   * changes, so it can update the 3D cursor representation.
   *
   * @see <a href="https://www.x.org/releases/X11R7.6/doc/fixesproto/fixesproto.txt">XFixesSelectCursorInput</a>
   */
  public void selectCursorInput (Window window, int event_mask) {
    Request request = new Request (display, major_opcode, 3, 3);
    request.write4 (window.id);
    request.write4 (event_mask);
    display.send_request (request);
  }

  /**
   * XFixes CursorNotify event (event code {@code first_event + 1}). Delivered
   * after {@link #selectCursorInput} when the cursor image changes.
   *
   * <pre>
   * typedef struct {
   *     CARD8   type;             // 0
   *     CARD8   subtype;          // 1  (SetCursorNotify = 0)
   *     CARD16  sequenceNumber;   // 2
   *     Window  window;           // 4
   *     CARD32  cursorSerial;     // 8
   *     Time    timestamp;        // 12
   *     Atom    name;             // 16  (XFixes 2.0+)
   *     CARD32  pad1;             // 20
   * } xXFixesCursorNotifyEvent;
   * </pre>
   */
  public static class CursorNotifyEvent extends Event {
    public CursorNotifyEvent (Display display, byte [] data) {
      super (display, data, 4);
    }
    /** SetCursorNotify subtype discriminator at byte 1. */
    public int subtype () { return read1 (1); }
    /** Monotonically increasing serial for each distinct cursor image. */
    public int cursor_serial () { return read4 (8); }
    /** Server timestamp of the cursor change. */
    public int timestamp () { return read4 (12); }
    /** Cursor name atom (0 if the cursor is unnamed), XFixes 2.0+. */
    public int name_atom () { return read4 (16); }
  }

/*
typedef struct {
    CARD8   reqType;
    CARD8   xfixesReqType;
    CARD16  length B16;
    Region  region B32;
    // LISTofRECTANGLE 
} xXFixesCreateRegionReq;

#define sz_xXFixesCreateRegionReq	8
*/
  public int X11FixesCreateRegion (Rectangle[] rect, int nrrect) {
    int region = display.allocate_id(this);
    Request request = new Request (display, major_opcode, 5,2 + (nrrect<<2)); 
    request.write4 (region);       
    if(rect != null)
    {
        for(int i=0; i < nrrect; i++)
        {
           request.write2 (rect[i].x);
           request.write2 (rect[i].y);
           request.write2 (rect[i].width);
           request.write2 (rect[i].height);
        }
    }
    display.send_request (request);
    return region;
  }
  
 /*
 typedef struct {
    CARD8   reqType;
    CARD8   xfixesReqType;
    CARD16  length B16;
    Region  region B32;
    Pixmap  bitmap B32;
} xXFixesCreateRegionFromBitmapReq;

#define sz_xXFixesCreateRegionFromBitmapReq	12
*/
   public int X11FixesCreateRegionFromBitmap (Pixmap pixmap) {
    int region = display.allocate_id(this);
    Request request = new Request (display, major_opcode, 6,3); 
    request.write4 (region);
    request.write4 (pixmap.id);       
    
    display.send_request (request);
    return region;
  }
/*
typedef struct {
    CARD8   reqType;
    CARD8   xfixesReqType;
    CARD16  length B16;
    Region  region B32;
    Window  window B32;
    CARD8   kind;
    CARD8   pad1;
    CARD16  pad2 B16;
} xXFixesCreateRegionFromWindowReq;
*/

  public int X11FixesCreateRegionFromWindow (Window win,byte kind) {
    int region = display.allocate_id(this);
    Request request = new Request (display, major_opcode, 7,4); 
    request.write4 (region);
    request.write4 (win.id);
    request.write1 (kind);    
    
    display.send_request (request);
    return region;
  }

/*
  typedef struct {
    CARD8   reqType;
    CARD8   xfixesReqType;
    CARD16  length B16;
    Region  region B32;
} xXFixesDestroyRegionReq;

#define sz_xXFixesDestroyRegionReq	8
*/
  public void X11FixesDestroyRegion (int Region) {    
    Request request = new Request (display, major_opcode, 10,2); 
    request.write4 (Region);
    
    display.send_request (request);    
  }
  
/*
  typedef struct {
    CARD8   reqType;
    CARD8   xfixesReqType;
    CARD16  length B16;
    Region  region B32;
    // LISTofRECTANGLE
} xXFixesSetRegionReq;
#define sz_xXFixesSetRegionReq		8
*/

public void X11FixesSetRegion (int Region, Rectangle[] rect, int nrrect) {    
    Request request = new Request (display, major_opcode, 11,2 + (nrrect<<2)); 
    request.write4 (Region);
    if(rect != null)
    {
        for(int i=0; i < rect.length; i++)
        {
           request.write2 (rect[i].x);
           request.write2 (rect[i].y);
           request.write2 (rect[i].width);
           request.write2 (rect[i].height);
        }
    }    
    
    display.send_request (request);    
  }
  
  
/*
  typedef struct {
    CARD8   reqType;
    CARD8   xfixesReqType;
    CARD16  length B16;
    Region  source B32;
    Region  destination B32;
} xXFixesCopyRegionReq;

#define sz_xXFixesCopyRegionReq		12
*/

  public void X11FixesCopyRegion (int dest, int src) {    
    Request request = new Request (display, major_opcode, 12,3); 
    request.write4 (src);
    request.write4 (dest);
    
    display.send_request (request);    
  }

/*
typedef struct {
    CARD8   reqType;
    CARD8   xfixesReqType;
    CARD16  length B16;
    Region  source1 B32;
    Region  source2 B32;
    Region  destination B32;
} xXFixesUnionRegionReq,
  xXFixesIntersectRegionReq,
  xXFixesSubtractRegionReq;

#define sz_xXFixesCombineRegionReq	16
*/
  
  
  public void X11FixesUnionRegion (int dest, int src1, int src2) {    
    Request request = new Request (display, major_opcode, 13,4); 
    request.write4 (src1);
    request.write4 (src2);
    request.write4 (dest);
    
    display.send_request (request);    
  }
  
  public void X11FixesSubtractRegion (int dest, int src1, int src2) {    
    Request request = new Request (display, major_opcode, 14,4); 
    request.write4 (src1);
    request.write4 (src2);
    request.write4 (dest);
    
    display.send_request (request);    
  }
  
  public void X11FixesIntersectRegion (int dest, int src1, int src2) {    
    Request request = new Request (display, major_opcode, 15,4); 
    request.write4 (src1);
    request.write4 (src2);
    request.write4 (dest);
    
    display.send_request (request);    
  }
  
  
  
  
  public static class FetchRegionReply extends Data {
    public FetchRegionReply (Data data) { super (data); } 
    
    public int rectangle_count () { return (read4 (4) >> 1); }
  
  
    /**
     * 
     */
    public Enum rectangles () {
      return new Enum (this, 32, rectangle_count ()) {
        public Object next () {
          int x = this.read2 (0);
          int y = this.read2 (2);
          int width = this.read2 (4);
          int height = this.read2 (6);
          Rectangle rectangle = new Rectangle (x, y, width, height);
  
          inc (8);
          return rectangle;
        }
      };
    }
  }
  
  
  /*
        typedef struct {
            CARD8   reqType;
            CARD8   xfixesReqType;
            CARD16  length B16;
            Region  region B32;
        } xXFixesFetchRegionReq;
        
        #define sz_xXFixesFetchRegionReq	8
        
        typedef struct {
            BYTE    type;    X_Reply 
            BYTE    pad1;
            CARD16  sequenceNumber B16;
            CARD32  length B32;
            INT16   x B16, y B16;
            CARD16  width B16, height B16;
            CARD32  pad2 B32;
            CARD32  pad3 B32;
            CARD32  pad4 B32;
            CARD32  pad5 B32;
        } xXFixesFetchRegionReply;
  */
  
//  public FetchRegionReply X11FixesFetchRegion (int region, int kind) {
//    Request request = new Request (display, major_opcode, 19, 2);
//    request.write4 (region);
//    
//    Data reply = display.read_reply(request);
//
//    return new FetchRegionReply (reply);
//  }

  public String more_string () {
    return "\n  client-version: " 
      + CLIENT_MAJOR_VERSION + "." + CLIENT_MINOR_VERSION
      + "\n  server-version: "
      + server_major_version + "." + server_minor_version;
  }


  /* (non-Javadoc)
   * Decodes XFixes extension events. XFixes defines two events:
   * SelectionNotify (first_event + 0) and CursorNotify (first_event + 1).
   * Only CursorNotify is modelled in detail; any other event is wrapped in a
   * generic {@link Event} so it is dispatched (and can be logged) rather than
   * silently dropped by the reader thread.
   */
  public Event build(Display display, byte[] data, int code)
  {
     if (code == first_event + 1) {
        return new CursorNotifyEvent (display, data);
     }
     return new Event (display, data, 4);
  }
}
