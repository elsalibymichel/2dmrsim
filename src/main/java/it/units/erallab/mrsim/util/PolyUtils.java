/*
 * Copyright 2022 Eric Medvet <eric.medvet@gmail.com> (as eric)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.units.erallab.mrsim.util;

import it.units.erallab.mrsim.core.geometry.Path;
import it.units.erallab.mrsim.core.geometry.Point;
import it.units.erallab.mrsim.core.geometry.Poly;
import org.dyn4j.geometry.Convex;
import org.dyn4j.geometry.Polygon;
import org.dyn4j.geometry.Triangle;
import org.dyn4j.geometry.Vector2;
import org.dyn4j.geometry.decompose.SweepLine;
import org.dyn4j.geometry.decompose.Triangulator;

import java.util.*;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * @author "Eric Medvet" on 2022/07/10 for 2dmrsim
 */
public class PolyUtils {

  public final static double TERRAIN_BORDER_HEIGHT = 100d;
  public static final int TERRAIN_WIDTH = 2000;
  public static final double TERRAIN_BORDER_WIDTH = 10d;
  public static final double TERRAIN_START_LENGTH = 10d;

  private PolyUtils() {
  }

  public static Poly createTerrain(String name) {
    return createTerrain(name, TERRAIN_WIDTH, TERRAIN_BORDER_HEIGHT, TERRAIN_BORDER_WIDTH, TERRAIN_BORDER_HEIGHT);
  }

  public static Poly createTerrain(String name, double terrainW, double terrainH, double borderW, double borderH) {
    String flat = "flat";
    String hilly = "hilly-(?<h>[0-9]+(\\.[0-9]+)?)-(?<w>[0-9]+(\\.[0-9]+)?)-(?<seed>[0-9]+)";
    String steppy = "steppy-(?<h>[0-9]+(\\.[0-9]+)?)-(?<w>[0-9]+(\\.[0-9]+)?)-(?<seed>[0-9]+)";
    String downhill = "downhill-(?<angle>[0-9]+(\\.[0-9]+)?)";
    String uphill = "uphill-(?<angle>[0-9]+(\\.[0-9]+)?)";
    Map<String, String> params;
    Path path = new Path(new Point(0, 0));
    path = path
        .moveTo(0, borderH)
        .moveTo(borderW, 0)
        .moveTo(0, -borderH);
    //noinspection UnusedAssignment
    if ((params = StringUtils.params(flat, name)) != null) {
      path = path.moveTo(new Point(terrainW, 0));
    } else if ((params = StringUtils.params(hilly, name)) != null) {
      double h = Double.parseDouble(params.get("h"));
      double w = Double.parseDouble(params.get("w"));
      RandomGenerator random = new Random(Integer.parseInt(params.get("seed")));
      double dW = 0d;
      while (dW < terrainW) {
        double sW = Math.max(1d, (random.nextGaussian() * 0.25 + 1) * w);
        double sH = random.nextGaussian() * h;
        dW = dW + sW;
        path = path.moveTo(sW, sH);
      }
    } else if ((params = StringUtils.params(steppy, name)) != null) {
      double h = Double.parseDouble(params.get("h"));
      double w = Double.parseDouble(params.get("w"));
      RandomGenerator random = new Random(Integer.parseInt(params.get("seed")));
      double dW = 0d;
      while (dW < terrainW) {
        double sW = Math.max(1d, (random.nextGaussian() * 0.25 + 1) * w);
        double sH = random.nextGaussian() * h;
        dW = dW + sW;
        path = path
            .moveTo(sW, 0)
            .moveTo(0, sH);
      }
    } else if ((params = StringUtils.params(downhill, name)) != null) {
      double angle = Double.parseDouble(params.get("angle"));
      path = path.moveTo(terrainW, -terrainW * Math.sin(angle / 180 * Math.PI));
    } else if ((params = StringUtils.params(uphill, name)) != null) {
      double angle = Double.parseDouble(params.get("angle"));
      path = path.moveTo(terrainW, terrainW * Math.sin(angle / 180 * Math.PI));
    } else {
      throw new IllegalArgumentException(String.format("Unknown terrain name: %s", name));
    }
    path = path
        .moveTo(0, borderH)
        .moveTo(borderW, 0)
        .moveTo(0, -borderH);
    double maxX = Arrays.stream(path.points()).mapToDouble(Point::x).max().orElse(borderW);
    double minY = Arrays.stream(path.points()).mapToDouble(Point::y).min().orElse(borderW);
    path = path
        .add(maxX, minY - terrainH)
        .moveTo(-maxX, 0);
    return path.toPoly();
  }

  public static Set<Poly> decompose(Poly poly) {
    Triangulator triangulator = new SweepLine();
    List<Triangle> triangles = triangulator.triangulate(
        Arrays.stream(poly.vertexes()).map(p -> new Vector2(p.x(), p.y())).toArray(Vector2[]::new)
    );
    return triangles.stream()
        .map(c -> new Poly(
            Arrays.stream(c.getVertices()).map(v -> new Point(v.x, v.y)).toArray(Point[]::new)
        ))
        .collect(Collectors.toSet());
  }

  private static double angle(Point a, Point b, Point c) {
    double angle = c.diff(b).direction() - a.diff(b).direction();
    return angle > 0 ? angle : (angle + 2d * Math.PI);
  }

  public static Poly makeCounterClockwise(Poly poly) {
    Point c = poly.center();
    double sum = 0d;
    for (int i = 1; i < poly.vertexes().length; i++) {
      sum = sum + angle(poly.vertexes()[i], c, poly.vertexes()[i - 1]);
    }
    sum = sum + angle(poly.vertexes()[0], c, poly.vertexes()[poly.vertexes().length - 1]);
    if (sum >= 0) {
      return poly;
    }
    Point[] vertexes = new Point[poly.vertexes().length];
    for (int i = 0; i < vertexes.length; i++) {
      vertexes[i] = poly.vertexes()[vertexes.length - 1 - i];
    }
    return new Poly(vertexes);
  }


}