package org.jdesktop.wonderland.common.cell.config.jme;

import com.jme.renderer.ColorRGBA;
import com.jme.scene.state.MaterialState;
import java.io.Serializable;

public class MaterialJME implements Serializable {
   private ColorRGBA diffuseColor;
   private ColorRGBA ambientColor;
   private ColorRGBA specularColor;
   private ColorRGBA emissiveColor;
   private float shininess;

   public MaterialJME() {
      this((ColorRGBA)null, (ColorRGBA)null, (ColorRGBA)null, (ColorRGBA)null, 0.0F);
   }

   public MaterialJME(ColorRGBA diffuseColor, ColorRGBA ambientColor, ColorRGBA specularColor, ColorRGBA emissiveColor, float shininess) {
      this.diffuseColor = diffuseColor;
      this.ambientColor = ambientColor;
      this.specularColor = specularColor;
      this.emissiveColor = emissiveColor;
      this.shininess = shininess;
   }

   public ColorRGBA getDiffuseColor() {
      return this.diffuseColor;
   }

   public ColorRGBA getAmbientColor() {
      return this.ambientColor;
   }

   public ColorRGBA getSpecularColor() {
      return this.specularColor;
   }

   public ColorRGBA getEmissiveColor() {
      return this.emissiveColor;
   }

   public float getShininess() {
      return this.shininess;
   }

   public MaterialState apply(MaterialState state) {
      assert state != null;

      if (this.diffuseColor != null) {
         state.setDiffuse(this.diffuseColor);
      }

      if (this.ambientColor != null) {
         state.setAmbient(this.ambientColor);
      }

      if (this.specularColor != null) {
         state.setSpecular(this.specularColor);
      }

      if (this.emissiveColor != null) {
         state.setEmissive(this.emissiveColor);
      }

      state.setShininess(this.shininess);
      return state;
   }
}
