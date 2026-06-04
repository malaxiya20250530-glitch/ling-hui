Shader "LingHui/ToonCharacter"
{
    Properties
    {
        _MainTex ("主纹理", 2D) = "white" {}
        _RampTex ("渐变贴图", 2D) = "white" {}
        _ShadowColor ("阴影色", Color) = (0.3, 0.3, 0.4, 1)
        _OutlineColor ("描边色", Color) = (0.1, 0.1, 0.15, 1)
        _OutlineWidth ("描边宽度", Range(0, 0.1)) = 0.005
        _RimColor ("边缘光色", Color) = (0.8, 0.9, 1, 1)
        _RimPower ("边缘光强度", Range(0.1, 8)) = 3
        _SpecularColor ("高光色", Color) = (1, 1, 1, 1)
        _SpecularPower ("高光强度", Range(1, 64)) = 32
    }

    SubShader
    {
        Tags { "RenderType"="Opaque" "Queue"="Geometry" }

        // ── Pass 1: 描边（背面膨胀法）──
        Pass
        {
            Name "Outline"
            Cull Front

            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #include "UnityCG.cginc"

            struct appdata { float4 vertex : POSITION; float3 normal : NORMAL; };
            struct v2f { float4 pos : SV_POSITION; };

            float _OutlineWidth;
            fixed4 _OutlineColor;

            v2f vert(appdata v)
            {
                v2f o;
                float3 normal = normalize(mul((float3x3)UNITY_MATRIX_IT_MV, v.normal));
                float2 offset = TransformViewToProjection(normal.xy);
                o.pos = UnityObjectToClipPos(v.vertex);
                o.pos.xy += offset * _OutlineWidth * o.pos.w;
                return o;
            }

            fixed4 frag(v2f i) : SV_Target { return _OutlineColor; }
            ENDCG
        }

        // ── Pass 2: 主体（渐变漫反射 + 边缘光 + Blinn-Phong 高光）──
        Pass
        {
            Name "ForwardBase"
            Tags { "LightMode"="ForwardBase" }
            Cull Back

            CGPROGRAM
            #pragma vertex vert
            #pragma fragment frag
            #pragma multi_compile_fwdbase
            #include "UnityCG.cginc"
            #include "Lighting.cginc"
            #include "AutoLight.cginc"

            struct appdata { float4 vertex : POSITION; float3 normal : NORMAL; float2 uv : TEXCOORD0; };
            struct v2f
            {
                float4 pos : SV_POSITION;
                float2 uv : TEXCOORD0;
                float3 worldNormal : TEXCOORD1;
                float3 worldPos : TEXCOORD2;
                float3 viewDir : TEXCOORD3;
                SHADOW_COORDS(4)
            };

            sampler2D _MainTex;
            sampler2D _RampTex;
            fixed4 _ShadowColor;
            fixed4 _RimColor;
            float _RimPower;
            fixed4 _SpecularColor;
            float _SpecularPower;

            v2f vert(appdata v)
            {
                v2f o;
                o.pos = UnityObjectToClipPos(v.vertex);
                o.uv = v.uv;
                o.worldNormal = UnityObjectToWorldNormal(v.normal);
                o.worldPos = mul(unity_ObjectToWorld, v.vertex).xyz;
                o.viewDir = normalize(UnityWorldSpaceViewDir(o.worldPos));
                TRANSFER_SHADOW(o);
                return o;
            }

            fixed4 frag(v2f i) : SV_Target
            {
                fixed4 albedo = tex2D(_MainTex, i.uv);
                float3 normal = normalize(i.worldNormal);
                float3 lightDir = normalize(_WorldSpaceLightPos0.xyz);
                float3 viewDir = normalize(i.viewDir);

                // 半 Lambert → 渐变贴图采样
                float halfLambert = dot(normal, lightDir) * 0.5 + 0.5;
                float shadowAtten = SHADOW_ATTENUATION(i);
                float rampUV = halfLambert * shadowAtten;
                fixed3 rampColor = tex2D(_RampTex, float2(rampUV, 0.5)).rgb;
                fixed3 diffuse = rampColor * _LightColor0.rgb;

                // 阴影混合
                fixed3 shadowed = lerp(_ShadowColor.rgb, fixed3(1,1,1), rampColor);
                diffuse *= shadowed;

                // Blinn-Phong 高光
                float3 halfVec = normalize(lightDir + viewDir);
                float spec = pow(max(dot(normal, halfVec), 0), _SpecularPower);
                fixed3 specular = _SpecularColor.rgb * spec * _LightColor0.rgb;

                // 边缘光
                float rim = 1.0 - saturate(dot(normal, viewDir));
                rim = pow(rim, _RimPower);
                fixed3 rimLight = _RimColor.rgb * rim;

                fixed3 finalColor = albedo.rgb * diffuse + specular + rimLight;
                return fixed4(finalColor, albedo.a);
            }
            ENDCG
        }

        UsePass "Legacy Shaders/VertexLit/SHADOWCASTER"
    }
    Fallback "Diffuse"
}
