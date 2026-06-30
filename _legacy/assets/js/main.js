

(function ($) {
	"use strict";

	var windowOn = $(window);
	////////////////////////////////////////////////////
	// 01. PreLoader Js
	windowOn.on('load', function () {
		$("#loading").fadeOut(500);
	});


	////////////////////////////////////////////////////
	// 02. Common Js

	$("[data-background").each(function () {
		$(this).css("background-image", "url( " + $(this).attr("data-background") + "  )");
	});

	$("[data-width]").each(function () {
		$(this).css("width", $(this).attr("data-width"));
	});

	$("[data-bg-color]").each(function () {
		$(this).css("background-color", $(this).attr("data-bg-color"));
	});

	$("[data-text-color]").each(function () {
		$(this).css("color", $(this).attr("data-text-color"));
	});

	$(".has-img").each(function () {
		var imgSrc = $(this).attr("data-menu-img");
		var img = `<img class="mega-menu-img" src="${imgSrc}" alt="img">`;
		$(this).append(img);

	});


	$('.wp-menu nav > ul > li').slice(-4).addClass('menu-last');


	$('.hamburger-toggle').on('click', function () {
		$('.header-side-menu').slideToggle('header-side-menu');
	});


	// menu-js
	if ($('.main-menu-content').length && $('.main-menu-mobile').length) {
		let navContent = document.querySelector(".main-menu-content").outerHTML;
		let mobileNavContainer = document.querySelector(".main-menu-mobile");
		mobileNavContainer.innerHTML = navContent;


		let arrow = $(".main-menu-mobile .has-dropdown > a");

		arrow.each(function () {
			let self = $(this);
			let arrowBtn = document.createElement("BUTTON");
			arrowBtn.classList.add("dropdown-toggle-btn");
			arrowBtn.innerHTML = "<i class='fa-regular fa-angle-right'></i>";

			self.append(function () {
				return arrowBtn;
			});

			self.find("button").on("click", function (e) {
				e.preventDefault();
				let self = $(this);
				self.toggleClass("dropdown-opened");
				self.parent().toggleClass("expanded");
				self.parent().parent().addClass("dropdown-opened").siblings().removeClass("dropdown-opened");
				self.parent().parent().children(".submenu").slideToggle();


			});

		});
	}

	////////////////////////////////////////////////////
	// 03. Offcanvas Js
	$(".offcanvas-open-btn").on("click", function () {
		$(".offcanvas__area").addClass("offcanvas-opened");
		$(".body-overlay").addClass("opened");
	});
	$(".offcanvas-close-btn").on("click", function () {
		$(".offcanvas__area").removeClass("offcanvas-opened");
		$(".body-overlay").removeClass("opened");
	});

	////////////////////////////////////////////////////
	// 04. Click To Down
	function scrollNav() {
		$('.banner-scroll-mouse a').click(function () {
			$(".banner-scroll-mouse a.active").removeClass("active");
			$(this).addClass("active");

			$('html, body').stop().animate({
				scrollTop: $($(this).attr('href')).offset().top - 0
			}, 300);
			return false;
		});
	}
	scrollNav();

	////////////////////////////////////////////////////
	// 05. One Page Scroll Js
	function scrollNav() {
		$('.onepage-menu li a').click(function () {
			$(".onepage-menu li a.active").removeClass("active");
			$(this).addClass("active");

			$('html, body').stop().animate({
				scrollTop: $($(this).attr('href')).offset().top - 96
			}, 300);
			return false;
		});
	}
	scrollNav();

	////////////////////////////////////////////////////
	// 06. Search Js
	$(".search-open-btn").on("click", function () {
		$(".search-area").addClass("opened");
		$(".body-overlay").addClass("opened");
	});
	$(".tpsearchbar__close").on("click", function () {
		$(".search-area").removeClass("opened");
		$(".body-overlay").removeClass("opened");
	});

	////////////////////////////////////////////////////
	// 07. cartmini Js
	$(".cartmini-open-btn").on("click", function () {
		$(".cartmini__area").addClass("cartmini-opened");
		$(".body-overlay").addClass("opened");
	});

	$(".cartmini-close-btn").on("click", function () {
		$(".cartmini__area").removeClass("cartmini-opened");
		$(".body-overlay").removeClass("opened");
	});

	////////////////////////////////////////////////////
	// 8. Show Coupon Toggle Js
	$('.checkout-coupon-form-reveal-btn').on('click', function () {
		$('#tpCheckoutCouponForm').slideToggle(400);
	});


	////////////////////////////////////////////////////
	// 09. Body overlay Js
	$(".body-overlay").on("click", function () {
		$(".offcanvas__area").removeClass("offcanvas-opened");
		$(".search-area").removeClass("opened");
		$(".cartmini__area").removeClass("cartmini-opened");
		$(".body-overlay").removeClass("opened");
	});
	$('.col-custom').on("click", function () {
		// $('#features-item-thumb').removeClass().addClass($(this).attr('rel'));
		$(this).addClass('active').siblings().removeClass('active');
	});


	////////////////////////////////////////////////////
	// 10. Sticky Header Js
	windowOn.on('scroll', function () {
		var scroll = $(window).scrollTop();
		if (scroll < 100) {
			$("#header-sticky").removeClass("header-sticky");
		} else {
			$("#header-sticky").addClass("header-sticky");
		}
	});



	////////////////////////////////////////////////////
	// 11. Theme Settings Js

	// settings append in body
	function tp_settings_append($x) {
		var settings = $('body');
		let dark;
		$x == true ? dark = 'd-block' : dark = 'd-none';
		var settings_html = `<div class="theme-settings-area transition-3">
		<div class="theme-wrapper">
		   <div class="theme-header text-center">
			  <h4 class="theme-header-title">Harry Theme Settings</h4>
		   </div>

		   <!-- THEME TOGGLER -->
		   <div class="theme-toggle mb-20 ${dark}">
			  <label class="theme-toggle-main" for="theme-toggler">
				 <span class="theme-toggle-dark active"><i class="fa-light fa-moon"></i> Dark</span>
					<input type="checkbox" id="theme-toggler">
					<i class="theme-toggle-slide"></i>
				 <span class="theme-toggle-light"><i class="fa-light fa-sun-bright"></i> Light</span>
			  </label>
		   </div>

		   <!--  RTL SETTINGS -->
		   <div class="theme-dir mb-20">
			  <label class="theme-dir-main" for="dir-toggler">
				 <span class="theme-dir-rtl"> RTL</span>
					<input type="checkbox" id="dir-toggler">
					<i class="theme-dir-slide"></i>
				 <span class="theme-dir-ltr active"> LTR</span>
			  </label>
		   </div>

		   <!-- COLOR SETTINGS -->
		   <div class="theme-settings">
			  <div class="theme-settings-wrapper">
				 <div class="theme-settings-open">
					<button class="theme-settings-open-btn">
					   <span class="theme-settings-gear">
						  <i class="fa-light fa-gear"></i>
					   </span>
					   <span class="theme-settings-close">
						  <i class="fa-regular fa-xmark"></i>
					   </span>
					</button>
				 </div>
				 <div class="row row-cols-4 gy-2 gx-2">
					<div class="col">
					   <div class="theme-color-item color-active">
						  <button class="theme-color-btn color-settings-btn d-none" data-color-default="#F50963" type="button" data-color="#F50963"></button>
						  <button class="theme-color-btn color-settings-btn" type="button" data-color="#F50963"></button>
					   </div>
					</div>
					<div class="col">
					   <div class="theme-color-item color-active">
						  <button class="theme-color-btn color-settings-btn" type="button" data-color="#008080"></button>
					   </div>
					</div>
					<div class="col">
					   <div class="theme-color-item color-active">
						  <button class="theme-color-btn color-settings-btn" type="button" data-color="#AB6C56"></button>
					   </div>
					</div>
					<div class="col">
					   <div class="theme-color-item color-active">
						  <button class="theme-color-btn color-settings-btn" type="button" data-color="#3661FC"></button>
					   </div>
					</div>
					<div class="col">
					   <div class="theme-color-item color-active">
						  <button class="theme-color-btn color-settings-btn" type="button" data-color="#2CAE76"></button>
					   </div>
					</div>
					<div class="col">
					   <div class="theme-color-item color-active">
						  <button class="theme-color-btn color-settings-btn" type="button" data-color="#FF5A1B"></button>
					   </div>
					</div>
					<div class="col">
                        <div class="theme-color-item color-active">
                           <button class="theme-color-btn color-settings-btn" type="button" data-color="#03041C"></button>
                        </div>
                     </div>
					<div class="col">
					   <div class="theme-color-item color-active">
						  <button class="theme-color-btn color-settings-btn" type="button" data-color="#ED212C"></button>
					   </div>
					</div>
				 </div>
			  </div>
			  <div class="theme-color-input">
				 <h6>Choose Custom Color</h6>
				 <input type="color" id="color-setings-input" value="#F50963">
				 <label id="theme-color-label" for="color-setings-input"></label>
			  </div>
		   </div>
		</div>
	 </div>`;
		settings.append(settings_html);
	}
	// tp_settings_append(true); // if want to enable dark light mode then send "true";

	// settings open btn
	$(".theme-settings-open-btn").on("click", function () {
		$(".theme-settings-area").toggleClass("settings-opened");
	});

	// rtl settings
	function tp_rtl_settings() {

		$('#dir-toggler').on("change", function () {
			toggle_rtl();

		});


		// set toggle theme scheme
		function tp_set_scheme(tp_dir) {
			localStorage.setItem('tp_dir', tp_dir);
			document.documentElement.setAttribute("dir", tp_dir);

			if (tp_dir === 'rtl') {
				var list = $("[href='assets/css/bootstrap.css']");
				$(list).attr("href", "assets/css/bootstrap-rtl.css");
			} else {
				var list = $("[href='assets/css/bootstrap.css']");
				$(list).attr("href", "assets/css/bootstrap.css");
			}
		}

		// toogle theme scheme
		function toggle_rtl() {
			if (localStorage.getItem('tp_dir') === 'rtl') {
				tp_set_scheme('ltr');
				var list = $("[href='assets/css/bootstrap-rtl.css']");
				$(list).attr("href", "assets/css/bootstrap.css");
			} else {
				tp_set_scheme('rtl');
				var list = $("[href='assets/css/bootstrap.css']");
				$(list).attr("href", "assets/css/bootstrap-rtl.css");
			}
		}

		// set the first theme scheme
		function tp_init_dir() {
			if (localStorage.getItem('tp_dir') === 'rtl') {
				tp_set_scheme('rtl');
				var list = $("[href='assets/css/bootstrap.css']");
				$(list).attr("href", "assets/css/bootstrap-rtl.css");
				document.getElementById('dir-toggler').checked = true;
			} else {
				tp_set_scheme('ltr');
				document.getElementById('dir-toggler').checked = false;
				var list = $("[href='assets/css/bootstrap.css']");
				$(list).attr("href", "assets/css/bootstrap.css");
			}
		}
		tp_init_dir();
	}
	if ($("#dir-toggler").length > 0) {
		tp_rtl_settings();
	}

	// dark light mode toggler
	function tp_theme_toggler() {

		$('#theme-toggler').on("change", function () {
			toggleTheme();

		});


		// set toggle theme scheme
		function tp_set_scheme(tp_theme) {
			localStorage.setItem('tp_theme_scheme', tp_theme);
			document.documentElement.setAttribute("theme", tp_theme);
		}

		// toogle theme scheme
		function toggleTheme() {
			if (localStorage.getItem('tp_theme_scheme') === 'theme-dark') {
				tp_set_scheme('theme-light');
			} else {
				tp_set_scheme('theme-dark');
			}
		}

		// set the first theme scheme
		function tp_init_theme() {
			if (localStorage.getItem('tp_theme_scheme') === 'theme-dark') {
				tp_set_scheme('theme-dark');
				document.getElementById('theme-toggler').checked = true;
			} else {
				tp_set_scheme('theme-light');
				document.getElementById('theme-toggler').checked = false;
			}
		}
		tp_init_theme();
	}
	if ($("#theme-toggler").length > 0) {
		tp_theme_toggler();
	}


	// color settings
	function tp_color_settings() {

		// set color scheme
		function tp_set_color(tp_color_scheme) {
			localStorage.setItem('tp_color_scheme', tp_color_scheme);
			document.querySelector(':root').style.setProperty('--theme-1', tp_color_scheme);
			document.getElementById("color-setings-input").value = tp_color_scheme;
			document.getElementById("theme-color-label").style.backgroundColor = tp_color_scheme;
		}

		// set color
		function tp_set_input() {
			var color = localStorage.getItem('tp_color_scheme');
			document.getElementById("color-setings-input").value = color;
			document.getElementById("theme-color-label").style.backgroundColor = color;


		}
		tp_set_input();

		function tp_init_color() {
			var defaultColor = $(".color-settings-btn").attr('data-color-default');
			var setColor = localStorage.getItem('tp_color_scheme');

			if (setColor != null) {

			} else {
				setColor = defaultColor;
			}

			if (defaultColor !== setColor) {
				document.querySelector(':root').style.setProperty('--theme-1', setColor);
				document.getElementById("color-setings-input").value = setColor;
				document.getElementById("theme-color-label").style.backgroundColor = setColor;
				tp_set_color(setColor);
			} else {
				document.querySelector(':root').style.setProperty('--theme-1', defaultColor);
				document.getElementById("color-setings-input").value = defaultColor;
				document.getElementById("theme-color-label").style.backgroundColor = defaultColor;
				tp_set_color(defaultColor);
			}
		}
		tp_init_color();


		let themeButtons = document.querySelectorAll('.color-settings-btn');

		themeButtons.forEach(color => {
			color.addEventListener('click', () => {
				let datacolor = color.getAttribute('data-color');
				document.querySelector(':root').style.setProperty('--theme-1', datacolor);
				document.getElementById("theme-color-label").style.backgroundColor = datacolor;
				tp_set_color(datacolor);
			});
		});



		const colorInput = document.querySelector('#color-setings-input');
		const colorVariable = '--theme-1';


		colorInput.addEventListener('change', function (e) {
			var clr = e.target.value;
			document.documentElement.style.setProperty(colorVariable, clr);
			tp_set_color(clr);
			tp_set_check(clr);
		});


		function tp_set_check(clr) {
			const arr = Array.from(document.querySelectorAll('[data-color]'));

			var a = localStorage.getItem('tp_color_scheme');

			let test = arr.map(color => {
				let datacolor = color.getAttribute('data-color');

				return datacolor;
			}).filter(color => color == a);

			var arrLength = test.length;

			if (arrLength == 0) {
				$('.color-active').removeClass('active');
			} else {
				$('.color-active').addClass('active');
			}
		}

		function tp_check_color() {
			var a = localStorage.getItem('tp_color_scheme');

			var list = $(`[data-color="${a}"]`);

			list.parent().addClass('active').parent().siblings().find('.color-active').removeClass('active')
		}
		tp_check_color();

		$('.color-active').on('click', function () {
			$(this).addClass('active').parent().siblings().find('.color-active').removeClass('active');
		});

	}
	if (($(".color-settings-btn").length > 0) && ($("#color-setings-input").length > 0) && ($("#theme-color-label").length > 0)) {
		tp_color_settings();
	}



	////////////////////////////////////////////////////
	// 12. Nice Select Js
	$('.header-search-category select').niceSelect();

	////////////////////////////////////////////////////
	// 13. Smooth Scroll Js
	function smoothSctoll() {
		$('.smooth a').on('click', function (event) {
			var target = $(this.getAttribute('href'));
			if (target.length) {
				event.preventDefault();
				$('html, body').stop().animate({
					scrollTop: target.offset().top - 120
				}, 1500);
			}
		});
	}
	smoothSctoll();

	function back_to_top() {
		var btn = $('#back_to_top');
		var btn_wrapper = $('.back-to-top-wrapper');

		windowOn.scroll(function () {
			if (windowOn.scrollTop() > 300) {
				btn_wrapper.addClass('back-to-top-btn-show');
			} else {
				btn_wrapper.removeClass('back-to-top-btn-show');
			}
		});

		btn.on('click', function (e) {
			e.preventDefault();
			$('html, body').animate({ scrollTop: 0 }, '300');
		});
	}
	back_to_top();

	var tp_rtl = localStorage.getItem('tp_dir');
	let rtl_setting = tp_rtl == 'rtl' ? true : false;

	////////////////////////////////////////////////////
	// 13. Price Filter Js
	$("#slider-range").slider({
		range: true,
		min: 0,
		max: 200,
		values: [20, 150],
		slide: function (event, ui) {
			$("#amount").val("$" + ui.values[0] + " - $" + ui.values[1]);
		}
	});
	$("#amount").val("$" + $("#slider-range").slider("values", 0) +
		" - $" + $("#slider-range").slider("values", 1));




	////////////////////////////////////////////////////
	// 14. Slider Activation Area Start
	$('.slider-active-4').slick({
		infinite: true,
		slidesToShow: 1,
		slidesToScroll: 1,
		arrows: true,
		fade: true,
		centerMode: true,
		prevArrow: `<button type="button" class="slider-3-button-prev"><svg width="16" height="14" viewBox="0 0 16 14" fill="none" xmlns="http://www.w3.org/2000/svg">
		   <path d="M1.00073 6.99989L15 6.99989" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
		   <path d="M6.64648 1.5L1.00011 6.99954L6.64648 12.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg></button>`,
		nextArrow: `<button type="button" class="slider-3-button-next"><svg width="16" height="14" viewBox="0 0 16 14" fill="none" xmlns="http://www.w3.org/2000/svg">
		<path d="M14.9993 6.99989L1 6.99989" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
		<path d="M9.35352 1.5L14.9999 6.99954L9.35352 12.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
		</svg></button>`,
		asNavFor: '.slider-nav-active',
		appendArrows: $('.slider-arrow-4'),

	});

	// home electronics
	var slider = new Swiper('.services-two-active', {
		slidesPerView: 4,
		spaceBetween: 0,
		loop: false,
		autoplay: {
			delay: 4000,
		},
		rtl: rtl_setting,
		// Navigation arrows
		pagination: {
			el: ".blog-main-slider-dot",
			clickable: true,
		},
		breakpoints: {
			'1200': {
				slidesPerView: 4,
			},
			'992': {
				slidesPerView: 3,
				autoplay: {
					delay: 3500,
				},
			},
			'768': {
				slidesPerView: 2,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Testimonial
	var slider = new Swiper('.testimonial-two-active', {
		slidesPerView: 3,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 3000,
		},
		rtl: rtl_setting,
		// Navigation arrows
		pagination: {
			el: ".blog-main-slider-dot",
			clickable: true,
		},
		breakpoints: {
			'1200': {
				slidesPerView: 3,
			},
			'992': {
				slidesPerView: 2,
			},
			'768': {
				slidesPerView: 2,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});



	// Testimonial
	var slider = new Swiper('.testimonial-4-active', {
		slidesPerView: 1,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 3000,
		},
		// Navigation arrows
		navigation: {
			nextEl: '.testimonial-4-nxt',
			prevEl: '.testimonial-4-prv',
		},
		rtl: rtl_setting,
		breakpoints: {
			'1200': {
				slidesPerView: 1,
			},
			'992': {
				slidesPerView: 1,
			},
			'768': {
				slidesPerView: 1,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});

	// Testimonial
	var slider = new Swiper('.testimonial-5-active', {
		slidesPerView: 2,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 300000,
		},
		// Navigation arrows
		navigation: {
			nextEl: '.testimonial-5-nxt',
			prevEl: '.testimonial-5-prv',
		},
		rtl: rtl_setting,
		breakpoints: {
			'1200': {
				slidesPerView: 2,
			},
			'992': {
				slidesPerView: 2,
			},
			'768': {
				slidesPerView: 2,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});

	// Team
	var slider = new Swiper('.team-3-active', {
		slidesPerView: 4,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 3500,
		},
		rtl: rtl_setting,
		breakpoints: {
			'1200': {
				slidesPerView: 4,
			},
			'992': {
				slidesPerView: 3,
			},
			'768': {
				slidesPerView: 3,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});

	// Brand
	var slider = new Swiper('.brand-3-active', {
		slidesPerView: 5,
		loop: true,
		autoplay: {
			delay: 3000,
		},
		rtl: rtl_setting,
		breakpoints: {
			'1200': {
				slidesPerView: 5,
			},
			'992': {
				slidesPerView: 4,
			},
			'768': {
				slidesPerView: 3,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Slider
	var slider = new Swiper('.slider-2-active', {
		slidesPerView: 1,
		effect: 'fade',
		loop: true,
		autoplay: {
			delay: 5000,
		},
		pagination: {
			el: ".slider-2-pagination",
			clickable: true,
		},
		rtl: rtl_setting,
		breakpoints: {
			'1200': {
				slidesPerView: 1,
			},
			'992': {
				slidesPerView: 1,
			},
			'768': {
				slidesPerView: 1,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Project
	var slider = new Swiper('.industries-active', {
		slidesPerView: 4,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 3000,
		},
		// Navigation arrows
		navigation: {
			nextEl: '.industries-nxt',
			prevEl: '.industries-prv',
		},
		rtl: rtl_setting,
		breakpoints: {
			'1200': {
				slidesPerView: 4,
			},
			'992': {
				slidesPerView: 4,
			},
			'768': {
				slidesPerView: 3,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Blog
	var slider = new Swiper('.blog-two-active', {
		slidesPerView: 4,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 4000,
		},
		rtl: rtl_setting,
		// Navigation arrows
		pagination: {
			el: ".blog-main-slider-dot",
			clickable: true,
		},
		breakpoints: {
			'1200': {
				slidesPerView: 4,
			},
			'992': {
				slidesPerView: 3,
			},
			'768': {
				slidesPerView: 2,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Blog
	var slider = new Swiper('.blog-carousel-active', {
		slidesPerView: 3,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 4000,
		},
		rtl: rtl_setting,
		// Navigation arrows
		pagination: {
			el: ".blog-carousel-dot",
			clickable: true,
		},
		breakpoints: {
			'1200': {
				slidesPerView: 3,
			},
			'992': {
				slidesPerView: 3,
			},
			'768': {
				slidesPerView: 2,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});

	// Slider
	var slider = new Swiper('.slider-active', {
		slidesPerView: 1,
		spaceBetween: 0,
		loop: true,
		effect: 'fade',
		autoplay: {
			delay: 4000,
		},
		rtl: rtl_setting,
		// Navigation arrows
		pagination: {
			el: ".slider-dot",
			clickable: true,
			renderBullet: function (index, className) {
				return '<span class="' + className + '">' + '<button>' + '0' + (index + 1) + '</button>' + "</span>";
			},
		},
		breakpoints: {
			'1200': {
				slidesPerView: 1,
			},
			'992': {
				slidesPerView: 1,
			},
			'768': {
				slidesPerView: 1,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Slider
	var slider = new Swiper('.project-2-slider-active', {
		slidesPerView: 4,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 4000,
		},
		rtl: rtl_setting,
		breakpoints: {
			'1200': {
				slidesPerView: 4,
			},
			'992': {
				slidesPerView: 3,
			},
			'768': {
				slidesPerView: 2,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Slider
	var slider = new Swiper('.project-3-slider-active', {
		slidesPerView: 4,
		spaceBetween: 30,
		loop: true,
		autoplay: {
			delay: 4000,
		},
		rtl: rtl_setting,
		breakpoints: {
			'1200': {
				slidesPerView: 4,
			},
			'992': {
				slidesPerView: 3,
			},
			'768': {
				slidesPerView: 2,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Review Js
	var swiperthumb = new Swiper(".review-avatar-active", {
		loop: true,
		spaceBetween: 10,
		slidesPerView: 5,
		freeMode: true,
		watchSlidesProgress: true,
		breakpoints: {
			'1400': {
				slidesPerView: 5,
			},
			'1200': {
				slidesPerView: 5,
			},
			'992': {
				slidesPerView: 3,
			},
			'768': {
				slidesPerView: 3,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});


	// Review Js
	var swipertestlist = new Swiper(".review-active", {
		loop: true,
		spaceBetween: 10,
		navigation: {
			nextEl: ".testi-button-next",
			prevEl: ".testi-button-prev",
		},
		thumbs: {
			swiper: swiperthumb,
		},
	});

	var swipertestlist = new Swiper(".testimonial-active", {
		loop: true,
		spaceBetween: 10,
		thumbs: {
			swiper: swiperthumb,
		},
	});

	var postboxSlider = new Swiper('.postbox-slider', {
		slidesPerView: 1,
		spaceBetween: 0,
		loop: true,
		rtl: rtl_setting,
		autoplay: {
			delay: 3000,
		},
		// Navigation arrows
		navigation: {
			nextEl: ".postbox-slider-button-next",
			prevEl: ".postbox-slider-button-prev",
		},
		breakpoints: {
			'1200': {
				slidesPerView: 1,
			},
			'992': {
				slidesPerView: 1,
			},
			'768': {
				slidesPerView: 1,
			},
			'576': {
				slidesPerView: 1,
			},
			'0': {
				slidesPerView: 1,
			},
		},
	});






	////////////////////////////////////////////////////
	// 15. Masonary Js
	$('.grid').imagesLoaded(function () {
		// init Isotope
		var $grid = $('.grid').isotope({
			itemSelector: '.grid-item',
			percentPosition: true,
			masonry: {
				// use outer width of grid-sizer for columnWidth
				columnWidth: '.grid-item',
			}
		});


		// filter items on button click
		$('.masonary-menu').on('click', 'button', function () {
			var filterValue = $(this).attr('data-filter');
			$grid.isotope({ filter: filterValue });
		});

		//for menu active class
		$('.masonary-menu button').on('click', function (event) {
			$(this).siblings('.active').removeClass('active');
			$(this).addClass('active');
			event.preventDefault();
		});

	});

	/* magnificPopup img view */
	$('.popup-image').magnificPopup({
		type: 'image',
		gallery: {
			enabled: true
		}
	});

	/* magnificPopup video view */
	$(".popup-video").magnificPopup({
		type: "iframe",
	});


	if ($('.scene').length > 0) {
		$('.scene').parallax({
			scalarX: 5.0,
			scalarY: 5.0,
		});
	};

	////////////////////////////////////////////////////
	// 16. Wow Js
	new WOW().init();

	function tp_ecommerce() {

		$('.cart-minus').on('click', function () {
			var $input = $(this).parent().find('input');
			var count = parseInt($input.val()) - 1;
			count = count < 1 ? 1 : count;
			$input.val(count);
			$input.change();
			return false;
		});

		$('.cart-plus').on('click', function () {
			var $input = $(this).parent().find('input');
			$input.val(parseInt($input.val()) + 1);
			$input.change();
			return false;
		});

		$('.checkout-payment-item label').on('click', function () {
			$(this).siblings('.checkout-payment-desc').slideToggle(400);

		});


		$('.color-variation-btn').on('click', function () {
			$(this).addClass('active').siblings().removeClass('active');
		});


		$('.size-variation-btn').on('click', function () {
			$(this).addClass('active').siblings().removeClass('active');
		});

		////////////////////////////////////////////////////
		// Show Login Toggle Js
		$('.checkout-login-form-reveal-btn').on('click', function () {
			$('#tpReturnCustomerLoginForm').slideToggle(400);
		});

		////////////////////////////////////////////////////
		//  Create An Account Toggle Js
		$('#cbox').on('click', function () {
			$('#cbox_info').slideToggle(900);
		});

		////////////////////////////////////////////////////
		// Shipping Box Toggle Js
		$('#ship-box').on('click', function () {
			$('#ship-box-info').slideToggle(1000);
		});
	}

	tp_ecommerce();



	////////////////////////////////////////////////////
	// 17. Counter Js
	new PureCounter();
	new PureCounter({
		filesizing: true,
		selector: ".filesizecount",
		pulse: 2,
	});

	////////////////////////////////////////////////////
	// 18. InHover Active Js
	$('.hover__active').on('mouseenter', function () {
		$(this).addClass('active').parent().siblings().find('.hover__active').removeClass('active');
	});

	$('.activebsba').on("click", function () {
		$('#services-item-thumb').removeClass().addClass($(this).attr('rel'));
		$(this).addClass('active').siblings().removeClass('active');
	});


	////////////////////////////////////////////////////
	// 19. Line Animation Js
	if ($('#marker').length > 0) {
		function tp_tab_line() {
			var marker = document.querySelector('#marker');
			var item = document.querySelectorAll('.menu-style-3  > nav > ul > li');
			var itemActive = document.querySelector('.menu-style-3  > nav > ul > li.active');

			function indicator(e) {
				marker.style.left = e.offsetLeft + "px";
				marker.style.width = e.offsetWidth + "px";
			}


			item.forEach(link => {
				link.addEventListener('mouseenter', (e) => {
					indicator(e.target);
				});

			});


			var activeNav = $('.menu-style-3 > nav > ul > li.active');
			var activewidth = $(activeNav).width();
			var activePadLeft = parseFloat($(activeNav).css('padding-left'));
			var activePadRight = parseFloat($(activeNav).css('padding-right'));
			var totalWidth = activewidth + activePadLeft + activePadRight;

			var precedingAnchorWidth = anchorWidthCounter();


			$(marker).css('display', 'block');

			$(marker).css('width', totalWidth);

			function anchorWidthCounter() {
				var anchorWidths = 0;
				var a;
				var aWidth;
				var aPadLeft;
				var aPadRight;
				var aTotalWidth;
				$('.menu-style-3 > nav > ul > li').each(function (index, elem) {
					var activeTest = $(elem).hasClass('active');
					marker.style.left = elem.offsetLeft + "px";
					if (activeTest) {
						// Break out of the each function.
						return false;
					}

					a = $(elem).find('li');
					aWidth = a.width();
					aPadLeft = parseFloat(a.css('padding-left'));
					aPadRight = parseFloat(a.css('padding-right'));
					aTotalWidth = aWidth + aPadLeft + aPadRight;

					anchorWidths = anchorWidths + aTotalWidth;

				});

				return anchorWidths;
			}
		}
		tp_tab_line();
	}


	if ($('#productTabMarker').length > 0) {
		function tp_tab_line_2() {
			var marker = document.querySelector('#productTabMarker');
			var item = document.querySelectorAll('.product-tab button');
			var itemActive = document.querySelector('.product-tab .nav-link.active');

			// rtl settings
			var tp_rtl = localStorage.getItem('tp_dir');
			let rtl_setting = tp_rtl == 'rtl' ? 'right' : 'left';

			function indicator(e) {
				marker.style.left = e.offsetLeft + "px";
				marker.style.width = e.offsetWidth + "px";
			}


			item.forEach(link => {
				link.addEventListener('click', (e) => {
					indicator(e.target);
				});
			});

			var activeNav = $('.nav-link.active');
			var activewidth = $(activeNav).width();
			var activePadLeft = parseFloat($(activeNav).css('padding-left'));
			var activePadRight = parseFloat($(activeNav).css('padding-right'));
			var totalWidth = activewidth + activePadLeft + activePadRight;

			var precedingAnchorWidth = anchorWidthCounter();


			$(marker).css('display', 'block');

			$(marker).css('width', totalWidth);

			function anchorWidthCounter() {
				var anchorWidths = 0;
				var a;
				var aWidth;
				var aPadLeft;
				var aPadRight;
				var aTotalWidth;
				$('.product-tab button').each(function (index, elem) {
					var activeTest = $(elem).hasClass('active');
					marker.style.left = elem.offsetLeft + "px";
					if (activeTest) {
						// Break out of the each function.
						return false;
					}

					a = $(elem).find('button');
					aWidth = a.width();
					aPadLeft = parseFloat(a.css('padding-left'));
					aPadRight = parseFloat(a.css('padding-right'));
					aTotalWidth = aWidth + aPadLeft + aPadRight;

					anchorWidths = anchorWidths + aTotalWidth;

				});

				return anchorWidths;
			}
		}
		tp_tab_line_2();
	}

	////////////////////////////////////////////////////
	// 20. Video Play Js
	var play = false;
	$('.video-toggle-btn').on('click', function () {

		if (play === false) {
			$('.slider-video').addClass('full-width');
			$(this).addClass('hide');
			play = true;

			$('.slider-video').find('video').each(function () {
				$(this).get(0).play();
			});
		} else {
			$('.slider-video').removeClass('full-width');
			$(this).removeClass('hide');
			play = false;
			$('.slider-video').find('video').each(function () {
				$(this).get(0).pause();
			});
		}

	});

	////////////////////////////////////////////////////
	// 21. Password Toggle Js
	if ($('#password-show-toggle').length > 0) {
		var btn = document.getElementById('password-show-toggle');

		btn.addEventListener('click', function (e) {

			var inputType = document.getElementById('tp_password');
			var openEye = document.getElementById('open-eye');
			var closeEye = document.getElementById('close-eye');

			if (inputType.type === "password") {
				inputType.type = "text";
				openEye.style.display = 'block';
				closeEye.style.display = 'none';
			} else {
				inputType.type = "password";
				openEye.style.display = 'none';
				closeEye.style.display = 'block';
			}
		});
	}

	////////////////////////////////////
	// headerHeight js
	if ($('.header-height').length > 0) {
		var headerHeight = document.querySelector(".header-height");

		var setHeaderHeight = headerHeight.offsetHeight;

		$(".header-height").each(function () {
			$(this).css({
				'height' : setHeaderHeight + 'px'
			});
		});
	}

	////////////////////////////////////
	// YTPlayer js
	if ($('.player').length > 0) {
		jQuery(function(){
			jQuery(".player").YTPlayer();
		});
	}


})(jQuery);
