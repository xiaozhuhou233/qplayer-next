// QPlayer Android compatibility adaptation, 2026-09-28. Original: Copyright 2025 Kyant, Apache-2.0.
/*
   Copyright 2025 Kyant

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.kyant.backdrop.internal

import com.kyant.backdrop.effects.GLASS_CAP_CURVATURE_SQUARED
import com.kyant.backdrop.effects.GLASS_CAP_PROFILE_NUMERATOR
import com.kyant.backdrop.effects.GLASS_DEPTH_NORMAL_WEIGHT
import com.kyant.backdrop.effects.GLASS_DISPERSION_SPREAD
import org.intellij.lang.annotations.Language

@Language("AGSL")
private const val RoundedRectSDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

// Extracted from base(1).apk w0.g.h (Kyant Backdrop, Apache-2.0).
// Only normalize(0) / sqrt roundoff guards differ: the demo's 160dp tile never
// hit its centre in the bevel, but QPlayer's 24–44dp controls can. Parameters,
// the circular profile, depth normal and single texture sample remain original.
@Language("AGSL")
internal const val ApkAdaptiveRefractionShaderString = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$RoundedRectSDF

float2 referenceNormal(float2 v) {
    return v * inversesqrt(max(dot(v, v), 0.00000001));
}
float2 referenceGrad(float2 coord, float2 halfSize, float radius) {
    float2 q = abs(coord) - (halfSize - float2(radius));
    if (q.x >= 0.0 || q.y >= 0.0) {
        return sign(coord) * referenceNormal(max(q, 0.0));
    }
    float gradX = step(q.y, q.x);
    return sign(coord) * float2(gradX, 1.0 - gradX);
}
float circleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}
half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) return content.eval(coord);
    sd = min(sd, 0.0);
    float d = circleMap(1.0 + sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = referenceNormal(referenceGrad(centeredCoord, halfSize, gradRadius)
        + depthEffect * referenceNormal(centeredCoord));
    return content.eval(coord + d * grad);
}
"""

// An elliptical cap's slope is proportional to (x/a², y/b²), not (x, y).
// Squared axis weights keep long pills from stretching the backdrop horizontally
// more just because they are wide. The dome joins the bevel with zero slope.
@Language("AGSL")
private const val ConvexBackdropCoord = """
float2 convexBackdropCoord(float2 coord, float2 centeredCoord, float2 halfSize, float strength) {
    float2 safeHalfSize = max(halfSize, float2(0.001));
    float2 normalizedCoord = centeredCoord / safeHalfSize;
    float2 axisWeight = min(safeHalfSize.x, safeHalfSize.y) / safeHalfSize;
    float dome = max(1.0 - dot(normalizedCoord, normalizedCoord), 0.0);
    return coord - centeredCoord * axisWeight * axisWeight * (strength * dome * dome);
}
"""

// Same one-sample lens (seven existing taps only when dispersion is enabled).
// The rational form of the cap avoids cancellation as a pressed lens fades in.
@Language("AGSL")
private const val GlassBevelRefraction = """
float glassBevelProfile(float u) {
    float u2 = clamp(u, 0.0, 1.0);
    u2 *= u2;
    return $GLASS_CAP_PROFILE_NUMERATOR * u2 / (1.0 + sqrt(1.0 - $GLASS_CAP_CURVATURE_SQUARED * u2));
}

float2 glassSafeNormal(float2 v) {
    return v * inversesqrt(max(dot(v, v), 0.00000001));
}

float2 glassBevelNormal(float2 p, float2 halfSize, float radius, float depth) {
    float2 q = abs(p) - (halfSize - float2(radius));
    float2 outer = max(q, 0.0);
    float2 normal;
    if (dot(outer, outer) > 0.00000001) {
        normal = glassSafeNormal(sign(p) * outer);
    } else {
        float useX = step(q.y, q.x);
        normal = sign(p) * float2(useX, 1.0 - useX);
    }
    float2 domeNormal = glassSafeNormal(p / max(halfSize * halfSize, float2(0.000001)));
    return glassSafeNormal(normal + $GLASS_DEPTH_NORMAL_WEIGHT * depth * domeNormal);
}
"""

@Language("AGSL")
internal const val RoundedRectRefractionShaderString = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float centerConvexity;

$RoundedRectSDF
$ConvexBackdropCoord
$GlassBevelRefraction

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);
    float2 convexCoord = convexBackdropCoord(coord, centeredCoord, halfSize, centerConvexity);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(convexCoord);
    }
    sd = min(sd, 0.0);
    
    float d = glassBevelProfile(1.0 + sd / refractionHeight) * refractionAmount;
    float2 grad = glassBevelNormal(centeredCoord, halfSize, radius, depthEffect);
    
    float2 refractedCoord = convexCoord + d * grad;
    return content.eval(refractedCoord);
}"""

@Language("AGSL")
internal val RoundedRectRefractionWithDispersionShaderString = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
uniform float centerConvexity;

$RoundedRectSDF
$ConvexBackdropCoord
$GlassBevelRefraction

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);
    float2 convexCoord = convexBackdropCoord(coord, centeredCoord, halfSize, centerConvexity);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(convexCoord);
    }
    sd = min(sd, 0.0);
    
    float d = glassBevelProfile(1.0 + sd / refractionHeight) * refractionAmount;
    float2 grad = glassBevelNormal(centeredCoord, halfSize, radius, depthEffect);
    
    float2 refractedCoord = convexCoord + d * grad;
    // Keep the seven colour taps close to the same curved surface, not a second
    // full-size displacement that can fold a colour channel back over itself.
    float dispersionIntensity = chromaticAberration * $GLASS_DISPERSION_SPREAD *
        clamp((centeredCoord.x * centeredCoord.y) / max(halfSize.x * halfSize.y, 0.000001), -1.0, 1.0);
    float2 dispersedCoord = d * grad * dispersionIntensity;
    
    half4 color = half4(0.0);
    
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;
    
    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;
    
    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;
    
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;
    
    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;
    
    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;
    
    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    
    return color;
}"""

@Language("AGSL")
internal const val DefaultHighlightShaderString = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float angle;
uniform float falloff;

$RoundedRectSDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""

@Language("AGSL")
internal const val AmbientHighlightShaderString = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float angle;
uniform float falloff;

$RoundedRectSDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    float t = step(0.0, d);
    return half4(t, t, t, 1.0) * intensity;
}"""
