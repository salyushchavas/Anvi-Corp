import Image from "next/image";
import Link from "next/link";
import { ArrowRight } from "lucide-react";

const posts = [
  { img: "/blog/1.png", title: "Why do you need IT consulting services?",       href: "#" },
  { img: "/blog/2.png", title: "Navigating the future of IT",                   href: "#" },
  { img: "/blog/3.png", title: "Solutions and strategies for IT challenges",    href: "#" },
  { img: "/blog/4.png", title: "Exploring the latest in tech innovation",       href: "#" },
];

export function BlogSection() {
  return (
    <section id="blog" className="py-20 lg:py-28 bg-ink-50">
      <div className="container">
        <div className="flex flex-col lg:flex-row lg:items-end lg:justify-between gap-6 mb-12">
          <div className="max-w-xl">
            <span className="inline-block text-xs font-semibold uppercase tracking-[0.2em] text-brand mb-3">
              What&apos;s new
            </span>
            <h2>Our blogs</h2>
          </div>
          <p className="max-w-md text-ink-400">
            Content designed with the <span className="text-brand font-semibold">aim to engage</span> and inform our audience on relevant topics.
          </p>
        </div>

        <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {posts.map(post => (
            <Link
              key={post.title}
              href={post.href}
              className="group flex flex-col rounded-3xl bg-white overflow-hidden shadow-card hover:shadow-cardHover transition-all hover:-translate-y-1 border border-ink-100"
            >
              <div className="aspect-[4/3] overflow-hidden">
                <Image
                  src={post.img}
                  alt=""
                  width={400}
                  height={300}
                  className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-105"
                />
              </div>
              <div className="p-6 flex-grow flex flex-col">
                <h3 className="text-lg leading-snug mb-4 flex-grow group-hover:text-brand transition">
                  {post.title}
                </h3>
                <span className="inline-flex items-center gap-1.5 text-sm font-semibold text-brand">
                  Read more <ArrowRight className="h-3.5 w-3.5 transition-transform group-hover:translate-x-1" />
                </span>
              </div>
            </Link>
          ))}
        </div>
      </div>
    </section>
  );
}
