<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE helpset PUBLIC "-//Sun Microsystems Inc.//DTD JavaHelp HelpSet Version 2.0//EN" "http://java.sun.com/products/javahelp/helpset_2_0.dtd">
<!--
  Project Looking Glass desktop HelpSet.

  The Help Center (org.jdesktop.lg3d.apps.help.HelpCenterPanel) loads this file
  from the classpath via HelpSet.findHelpSet(...) and hands it to a javax.help
  JHelp viewer, which is hosted on a SwingNode in the 3D desktop or in an MDI
  internal frame in the 2D/Swing desktop. The three navigators (Contents, Index,
  Search) are the standard JavaHelp views; the Search view reads the JavaSearch
  database generated at build time by the generateHelpSearchIndex Gradle task.
-->
<helpset version="2.0">
  <title>Project Looking Glass Help</title>

  <maps>
    <homeID>overview</homeID>
    <mapref location="map.jhm"/>
  </maps>

  <view>
    <name>TOC</name>
    <label>Contents</label>
    <type>javax.help.TOCView</type>
    <data>toc.xml</data>
  </view>

  <view>
    <name>Index</name>
    <label>Index</label>
    <type>javax.help.IndexView</type>
    <data>index.xml</data>
  </view>

  <view>
    <name>Search</name>
    <label>Search</label>
    <type>javax.help.SearchView</type>
    <data engine="com.sun.java.help.search.DefaultSearchEngine">JavaSearch</data>
  </view>
</helpset>
