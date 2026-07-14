'use client';

import { useEffect, useRef } from 'react';

/**
 * Particle/line-link background animation ported from the legacy index.html.
 * Renders an absolutely-positioned canvas that fills its containing element.
 * Parent must have `position: relative` (or absolute/fixed) and a defined size.
 */
export default function ParticleCanvas() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const rafRef = useRef<number | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const opts = {
      particleColor: 'rgb(251,155,71)',
      lineColor: 'rgb(251,155,71)',
      particleAmount: 60,
      defaultSpeed: 0.6,
      variantSpeed: 0.6,
      defaultRadius: 1.5,
      variantRadius: 1.5,
      linkRadius: 180,
    };

    const lineRgb = opts.lineColor.match(/\d+/g) ?? ['251', '155', '71'];
    let w = 0;
    let h = 0;

    type Particle = {
      x: number;
      y: number;
      vx: number;
      vy: number;
      r: number;
    };
    let particles: Particle[] = [];

    function sizeToParent() {
      if (!canvas) return;
      const parent = canvas.parentElement;
      if (!parent) return;
      const rect = parent.getBoundingClientRect();
      w = canvas.width = rect.width;
      h = canvas.height = rect.height;
    }

    function spawnParticles() {
      particles = [];
      for (let i = 0; i < opts.particleAmount; i++) {
        const speed = opts.defaultSpeed + Math.random() * opts.variantSpeed;
        const angle = Math.random() * Math.PI * 2;
        particles.push({
          x: Math.random() * w,
          y: Math.random() * h,
          vx: Math.cos(angle) * speed,
          vy: Math.sin(angle) * speed,
          r: opts.defaultRadius + Math.random() * opts.variantRadius,
        });
      }
    }

    function step(p: Particle) {
      if (p.x >= w || p.x <= 0) p.vx *= -1;
      if (p.y >= h || p.y <= 0) p.vy *= -1;
      if (p.x > w) p.x = w;
      if (p.y > h) p.y = h;
      if (p.x < 0) p.x = 0;
      if (p.y < 0) p.y = 0;
      p.x += p.vx;
      p.y += p.vy;
    }

    function drawParticle(p: Particle) {
      if (!ctx) return;
      ctx.beginPath();
      ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
      ctx.closePath();
      ctx.fillStyle = opts.particleColor;
      ctx.fill();
    }

    function linkPoints(p: Particle, others: Particle[]) {
      if (!ctx) return;
      for (let i = 0; i < others.length; i++) {
        const o = others[i];
        const dx = o.x - p.x;
        const dy = o.y - p.y;
        const d = Math.sqrt(dx * dx + dy * dy);
        const opacity = 1 - d / opts.linkRadius;
        if (opacity > 0) {
          ctx.lineWidth = 0.5;
          ctx.strokeStyle = `rgba(${lineRgb[0]},${lineRgb[1]},${lineRgb[2]},${opacity * 0.3})`;
          ctx.beginPath();
          ctx.moveTo(p.x, p.y);
          ctx.lineTo(o.x, o.y);
          ctx.closePath();
          ctx.stroke();
        }
      }
    }

    function frame() {
      if (!ctx) return;
      ctx.clearRect(0, 0, w, h);
      for (const p of particles) {
        step(p);
        drawParticle(p);
      }
      for (const p of particles) {
        linkPoints(p, particles);
      }
      rafRef.current = window.requestAnimationFrame(frame);
    }

    function onResize() {
      sizeToParent();
    }

    sizeToParent();
    spawnParticles();
    rafRef.current = window.requestAnimationFrame(frame);
    window.addEventListener('resize', onResize);

    return () => {
      window.removeEventListener('resize', onResize);
      if (rafRef.current !== null) {
        window.cancelAnimationFrame(rafRef.current);
      }
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 h-full w-full"
    />
  );
}
