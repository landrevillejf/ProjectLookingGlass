package org.jdesktop.lg3d.apps.blackgoat.component.folder;

import org.jogamp.vecmath.Vector3f;

import org.jdesktop.lg3d.apps.blackgoat.component.letter.PostitComponent3D;
import org.jdesktop.lg3d.apps.blackgoat.component.letter.SmallLetterComponent3D;
import org.jdesktop.lg3d.utils.c3danimation.NaturalMotionAnimation;
import org.jdesktop.lg3d.wg.Container3D;

/**
 * @author dai
 */

/**
 * Holds small paper messages in a box message box.
 */
public class FolderContainer3D extends Container3D implements Runnable {

	private int numLetters;
	
//	float xdim, ydim, zdim;
	
	/**
	 * 
	 * @param letterNum
	 * @return this
	 */
	public Container3D createLetter(int letterNum) {
		this.numLetters = letterNum;
		run();
		return this;
	}
	
	/**
	 * stack messages in a box gradually
	 */
	public void run(){
		float deli = 0.0f;
		float dim = 0.0f;
		float xdim, ydim, zdim;
		
		xdim = 0.009f;
		ydim = 0.009f;
		zdim = 0.009f;
		dim = xdim * 1.5f;
		deli = dim / (numLetters + 2);
		
		/**
		 * Creating messages in box
		 */
		setMouseEventPropagatable(true);
    	
		for (int i = 0; i < numLetters; i++) {
		//	deli = dim / (letterNum + 2);
			SmallLetterComponent3D comp = new SmallLetterComponent3D(i, dim, dim);
			comp.setAnimation(new NaturalMotionAnimation(2000));
			Vector3f position = new Vector3f(0.0f, 0.0f, -dim / 2 + deli * (i + 1));
			comp.changeTranslation(position, 1000);
			comp.setPosition(position);
			addChild(comp);
		}
	
	}

	/**
	 * Un attach postit from one cube's component.
	 * @param messageId
	 */
	public void unAttachPostit(int messageId){
		((PostitComponent3D)((SmallLetterComponent3D)getChild(messageId)).getChild(1)).changeVisible(false);
		((SmallLetterComponent3D)getChild(messageId)).unAttachPostit();
	}
	
	
	
}
